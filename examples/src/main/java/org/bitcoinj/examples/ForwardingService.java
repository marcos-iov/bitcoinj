/*
 * Copyright by the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.bitcoinj.examples;

import org.bitcoinj.base.BitcoinNetwork;
import org.bitcoinj.base.Sha256Hash;
import org.bitcoinj.base.Address;
import org.bitcoinj.base.Coin;
import org.bitcoinj.base.AddressParser;
import org.bitcoinj.core.Context;
import org.bitcoinj.core.Transaction;
import org.bitcoinj.kits.WalletAppKit;
import org.bitcoinj.utils.BriefLogFormatter;
import org.bitcoinj.wallet.CoinSelection;
import org.bitcoinj.wallet.CoinSelector;
import org.bitcoinj.wallet.SendRequest;
import org.bitcoinj.wallet.Wallet;
import org.bitcoinj.wallet.listeners.WalletCoinsReceivedEventListener;

import java.io.Closeable;
import java.io.File;

import static java.util.stream.Collectors.collectingAndThen;
import static java.util.stream.Collectors.toList;

/**
 * ForwardingService demonstrates basic usage of bitcoinj. It creates an SPV Wallet, listens on the network
 * and when it receives coins, simply sends them onwards to the address given on the command line.
 */
public class ForwardingService implements Closeable {
    static final String USAGE = "Usage: address-to-forward-to [mainnet|testnet|signet|regtest]";
    static final int REQUIRED_CONFIRMATIONS = 1;
    static final int MAX_CONNECTIONS = 4;
    private final BitcoinNetwork network;
    private final Address forwardingAddress;
    private volatile WalletAppKit kit;

    /**
     * Run the forwarding service as a command line tool
     * @param args See {@link #USAGE}
     */
    public static void main(String[] args) {
        // This line makes the log output more compact and easily read, especially when using the JDK log adapter.
        BriefLogFormatter.init();
        Context.propagate(new Context());

        if (args.length < 1 || args.length > 2) {
            System.err.println(USAGE);
            System.exit(1);
        }

        // Create and run the service, which will listen for transactions and forward coins until stopped
        try (ForwardingService forwardingService = new ForwardingService(args)) {
            forwardingService.run();
            // Wait for Control-C
            try {
                Thread.sleep(Long.MAX_VALUE);
            } catch (InterruptedException ignored) {}
        }
    }

    /**
     * Initialize by parsing the network and forwarding address command-line arguments.
     *
     * @param args the arguments from {@link #main(String[])}
     */
    public ForwardingService(String[] args) {
        if (args.length >= 2) {
            // If network was specified, validate address against network
            network = BitcoinNetwork.fromString(args[1]).orElseThrow();
            forwardingAddress = AddressParser.getDefault(network).parseAddress(args[0]);
        } else {
            // Else network not-specified, extract network from address
            forwardingAddress = AddressParser.getDefault().parseAddress(args[0]);
            network = (BitcoinNetwork) forwardingAddress.network();
        }
    }

    /**
     * Start the wallet and register the coin-forwarding listener.
     */
    public void run() {
        System.out.println("Network: " + network.id());
        System.out.println("Forwarding address: " + forwardingAddress);

        // Create and start the WalletKit
        kit = WalletAppKit.launch(network, new File("."), getPrefix(network), MAX_CONNECTIONS);

        // Add a listener that forwards received coins
        kit.wallet().addCoinsReceivedEventListener(this::coinForwardingListener);

        // After we start listening, we can tell the user the receiving address
        System.out.printf("Waiting to receive coins on: %s\n", kit.wallet().currentReceiveAddress());
        System.out.println("Press Ctrl-C to quit.");
    }

    /**
     * Close the service.
     * <p>
     * Note that {@link WalletAppKit#setAutoStop(boolean)} is set by default and installs a shutdown handler
     * via {@link Runtime#addShutdownHook(Thread)} so we do not need to worry about explicitly shutting down
     * the {@code WalletAppKit} if the process is terminated.
     */
    @Override
    public void close() {
        if (kit != null) {
            if (kit.isRunning()) {
                kit.wallet().removeCoinsReceivedEventListener(this::coinForwardingListener);
            }
            kit.close();
        }
    }

    /**
     * A listener to receive coins and forward them to the configured address.
     * Implements the {@link WalletCoinsReceivedEventListener} functional interface.
     * @param wallet The active wallet
     * @param incomingTx the received transaction
     * @param prevBalance wallet balance before this transaction (unused)
     * @param newBalance wallet balance after this transaction (unused)
     */
    private void coinForwardingListener(Wallet wallet, Transaction incomingTx, Coin prevBalance, Coin newBalance) {
        // Incoming transaction received, now "compose" (i.e. chain) a call to wait for required confirmations
        // The transaction "incomingTx" can either be pending, or included into a block (we didn't see the broadcast).
        Coin value = incomingTx.getValueSentToMe(wallet);
        System.out.printf("Received tx for %s : %s\n", value.toFriendlyString(), incomingTx);
        System.out.println("Transaction will be forwarded after it confirms.");
        System.out.println("Waiting for confirmation...");
        wallet.waitForConfirmations(incomingTx, REQUIRED_CONFIRMATIONS)
            .thenCompose(confidence -> {
                // Required confirmations received, now compose a call to broadcast the forwarding transaction
                System.out.printf("Incoming tx has received %d confirmations.\n", confidence.getDepthInBlocks());
                // Now send the coins onwards by sending exactly the outputs that have been sent to us
                SendRequest sendRequest = SendRequest.emptyWallet(forwardingAddress);
                sendRequest.coinSelector = forwardingCoinSelector(incomingTx.getTxId());
                System.out.printf("Creating outgoing transaction for %s...\n", forwardingAddress);
                return wallet.sendTransaction(sendRequest);
            })
            .thenCompose(broadcast -> {
                System.out.printf("Transaction %s is signed and is being delivered to %s...\n", broadcast.transaction().getTxId(), network);
                return broadcast.awaitRelayed(); // Wait until peers report they have seen the transaction
            })
            .thenAccept(broadcast ->
                System.out.printf("Sent %s onwards and acknowledged by peers, via transaction %s\n",
                        broadcast.transaction().getOutputSum().toFriendlyString(),
                        broadcast.transaction().getTxId())
            );
    }

    static String getPrefix(BitcoinNetwork network) {
        return String.format("forwarding-service-%s", network.toString());
    }

    /**
     * Create a CoinSelector that only returns outputs from a given parent transaction.
     * <p>
     * This is using the idea of partial function application to create a 2-argument function for coin selection
     * with a third, fixed argument of the transaction id.
     * @param parentTxId The parent transaction hash
     * @return a coin selector
     */
    static CoinSelector forwardingCoinSelector(Sha256Hash parentTxId) {
        return (target, candidates) -> candidates.stream()
                .filter(output -> output.getParentTransactionHash().equals(parentTxId))
                .collect(collectingAndThen(toList(), CoinSelection::new));
    }
}
