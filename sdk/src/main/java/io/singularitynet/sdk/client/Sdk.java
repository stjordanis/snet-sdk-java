package io.singularitynet.sdk.client;

import org.web3j.protocol.Web3j;
import io.ipfs.api.IPFS;

import io.singularitynet.sdk.contracts.Registry;
import io.singularitynet.sdk.contracts.MultiPartyEscrow;
import io.singularitynet.sdk.registry.RegistryContract;
import io.singularitynet.sdk.registry.MetadataStorage;
import io.singularitynet.sdk.registry.IpfsMetadataStorage;
import io.singularitynet.sdk.registry.MetadataProvider;
import io.singularitynet.sdk.registry.RegistryMetadataProvider;
import io.singularitynet.sdk.daemon.DaemonConnection;
import io.singularitynet.sdk.daemon.FirstEndpointDaemonConnection;
import io.singularitynet.sdk.daemon.PaymentChannelStateService;
import io.singularitynet.sdk.mpe.MultiPartyEscrowContract;
import io.singularitynet.sdk.mpe.PaymentChannelProvider;
import io.singularitynet.sdk.mpe.AskDaemonFirstPaymentChannelProvider;
import io.singularitynet.sdk.client.PaymentStrategy;
import io.singularitynet.sdk.client.ServiceClient;
import io.singularitynet.sdk.client.BaseServiceClient;
import io.singularitynet.sdk.ethereum.Signer;

public class Sdk {

    private final Web3j web3j;
    private final IPFS ipfs;
    private final Signer signer;
    private final Registry registry;
    private final MultiPartyEscrow mpe;

    public Sdk(Configuration config) {
        this(new ConfigurationDependencyFactory(config));
    }

    public Sdk(DependencyFactory factory) {
        this.web3j = factory.getWeb3j();
        this.ipfs = factory.getIpfs();
        this.signer = factory.getSigner();
        this.registry = factory.getRegistry();
        this.mpe = factory.getMultiPartyEscrow();
    }

    public Sdk(Web3j web3j, IPFS ipfs, Signer signer, Registry registry,
            MultiPartyEscrow mpe) {
        this.web3j = web3j;
        this.ipfs = ipfs;
        this.signer = signer;
        this.registry = registry;
        this.mpe = mpe;
    }

    public ServiceClient newServiceClient(String orgId, String serviceId,
            String endpointGroupName, PaymentStrategy paymentStrategy) {

        RegistryContract registryContract = new RegistryContract(registry);
        MultiPartyEscrowContract mpeContract = new MultiPartyEscrowContract(mpe);

        MetadataStorage metadataStorage = new IpfsMetadataStorage(ipfs);
        MetadataProvider metadataProvider = new RegistryMetadataProvider(
                orgId, serviceId, registryContract, metadataStorage);

        DaemonConnection connection = new FirstEndpointDaemonConnection(
                endpointGroupName, metadataProvider);
        PaymentChannelStateService stateService = new PaymentChannelStateService(
                connection, mpeContract, web3j, signer);
        PaymentChannelProvider paymentChannelProvider =
            new AskDaemonFirstPaymentChannelProvider(mpeContract, stateService);

        return new BaseServiceClient(connection, metadataProvider,
                paymentChannelProvider, paymentStrategy, signer); 
    }

    public void shutdown() {
        web3j.shutdown();
    }

}