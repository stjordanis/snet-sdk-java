package io.singularitynet.sdk.daemon;

import java.math.BigInteger;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.singularitynet.sdk.common.Preconditions;
import io.singularitynet.sdk.common.Utils;
import io.singularitynet.sdk.ethereum.CryptoUtils;
import io.singularitynet.sdk.ethereum.Address;
import io.singularitynet.sdk.ethereum.Signature;
import io.singularitynet.sdk.registry.PaymentGroupId;
import io.singularitynet.sdk.mpe.MultiPartyEscrowContract;
import io.singularitynet.sdk.mpe.PaymentChannel;
import io.singularitynet.sdk.mpe.PaymentChannelStateProvider;
import io.singularitynet.sdk.mpe.EscrowPayment;

/**
 * This class uses straightforward strategy to implement
 * PaymentChannelStateProvider interface. Each time client tries to get channel
 * state it makes gRPC call to the daemon. It is simple but ineffective
 * strategy. Nevetherless it can be useful when calls are rare and there are
 * few clients using the same payment channel. Under such conditions it allows
 * sharing channel state via daemon without additional synchronization.
 */
public class AskDaemonFirstPaymentChannelProvider implements PaymentChannelStateProvider {

    private final static Logger log = LoggerFactory.getLogger(AskDaemonFirstPaymentChannelProvider.class);

    private final MultiPartyEscrowContract mpe;
    private final PaymentChannelStateService stateService;

    /**
     * Constructor.
     * @param mpe MultiPartyEscrowContract instance which is used to get
     * channel state from the blockchain.
     * @param stateService client to the daemon payment channel state service.
     */
    public AskDaemonFirstPaymentChannelProvider(
            MultiPartyEscrowContract mpe,
            PaymentChannelStateService stateService) {
        this.mpe = mpe;
        this.stateService = stateService;
    }

    @Override
    public PaymentChannel getChannelStateById(BigInteger channelId) {
        log.debug("Getting the channel state, channelId: {}", channelId);
        PaymentChannel channel = mpe.getChannelById(channelId).get();
        PaymentChannelStateReply reply = stateService.getChannelState(channelId);
        if (!reply.hasCurrentSignedAmount()) {
            log.info("No payments on the channel in the daemon");
            Preconditions.checkState(channel.getNonce().compareTo(reply.getCurrentNonce()) >= 0,
                    "Daemon sent channel state which is newer then blockchain one. " +
                    "Channel id: %s", channel.getChannelId());
        } else {
            channel = mergeChannelState(channel, reply);
        }
        log.debug("Channel state, channel: {}", channel);
        return channel;
    }

    private static final BigInteger ONE = BigInteger.valueOf(1);

    private static PaymentChannel mergeChannelState(PaymentChannel blockchainState,
            PaymentChannelStateReply daemonState) {

        BigInteger spentAmount;

        if (blockchainState.getNonce().equals(daemonState.getCurrentNonce())) {
            verifySignature(blockchainState, daemonState.getCurrentSignedAmount(),
                    daemonState.getCurrentSignature(), "last current nonce");
            spentAmount = daemonState.getCurrentSignedAmount();
            return blockchainState.toBuilder()
                .setSpentAmount(daemonState.getCurrentSignedAmount())
                .build();
        } else {
            log.info("The channel nonce is different for the daemon and blockchain, blockchainState: {}, daemonState: {}",
                    blockchainState, daemonState);
            // TODO: test this case
            Preconditions.checkState(daemonState.getCurrentNonce().subtract(blockchainState.getNonce())
                    .equals(ONE), "Difference between current channel nonce " +
                    "and daemon channel nonce is bigger than 1. Channel id: %s",
                    blockchainState.getChannelId());

            verifySignature(blockchainState, daemonState.getOldNonceSignedAmount(),
                    daemonState.getOldNonceSignature(), "last old nonce");
            spentAmount = BigInteger.ZERO;

            if (daemonState.getCurrentSignature() != null) {
                verifySignature(blockchainState.toBuilder().setNonce(daemonState.getCurrentNonce()).build(),
                        daemonState.getCurrentSignedAmount(),
                        daemonState.getCurrentSignature(), "last current nonce");
                spentAmount = daemonState.getCurrentSignedAmount();
            }

            return blockchainState.toBuilder()
                .setNonce(daemonState.getCurrentNonce())
                .setValue(blockchainState.getValue().subtract(daemonState.getOldNonceSignedAmount()))
                .setSpentAmount(spentAmount)
                .build();
        }

    }

    private static void verifySignature(PaymentChannel channel, BigInteger amount,
            Signature signature, String type) {
        byte[] payment = EscrowPayment.getMessage(channel, amount);
        Address address = CryptoUtils.getSignerAddress(payment, signature);
        Preconditions.checkState(channel.getSigner().equals(address) ||
                channel.getSender().equals(address), 
                "Signature signer is not sender not signer. " + 
                "Daemon returned incorrect signature of the %s payment. " +
                "Channel: %s, Payment signer: %s", type, channel, address);
    }

}
