package net.javaguides.rpererv;

import software.amazon.awscdk.Stack;
import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.services.ec2.Vpc;
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
                .clusterName("photo-app-microservices-fargate-cluster") // name of the ECS cluster, same as the cluster name in the task definition
                .vpc(clusterStackProps.vpc()) // to associate the ECS cluster with the VPC created in the VpcStack
                .containerInsightsV2(ContainerInsights.ENABLED) // to enable container insights for monitoring and logging, which will create a CloudWatch log group with the name /aws/ecs/containerinsights/ECommerceCluster/logs, where ECommerceCluster is the name of the ECS cluster
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
