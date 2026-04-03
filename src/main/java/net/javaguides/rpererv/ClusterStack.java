package net.javaguides.rpererv;

import software.amazon.awscdk.Stack;
import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.services.ec2.Vpc;
import software.amazon.awscdk.services.ecs.CloudMapNamespaceOptions;
import software.amazon.awscdk.services.ecs.Cluster;
import software.amazon.awscdk.services.ecs.ClusterProps;
import software.amazon.awscdk.services.ecs.ContainerInsights;
import software.constructs.Construct;

/**
 * ClusterStack
 * <p>
 * Created by IntelliJ, Spring Framework Guru.
 *
 * @author architecture - pvraul
 * @version 14/02/2026 - 14:01
 * @since 1.17
 */
public class ClusterStack extends Stack {

    private final Cluster cluster;

    public ClusterStack(final Construct scope, final String id,
                        final StackProps props, ClusterStackProps clusterStackProps) {
        super(scope, id, props);

        this.cluster = new Cluster(this, "Cluster", ClusterProps.builder()
                .clusterName("photo-app-microservices-fargate-cluster")
                .vpc(clusterStackProps.vpc())
                .containerInsightsV2(ContainerInsights.ENABLED)
                // AÑADE ESTO:
                .defaultCloudMapNamespace(CloudMapNamespaceOptions.builder()
                        .name("local") // Este debe coincidir con el que pusiste en PhotoAlbumsMicroserviceStack para el service connect
                        .build())
                .build());

    }

    public Cluster getCluster() {
        return cluster;
    }
}

record ClusterStackProps(
        Vpc vpc
) {
}
