package net.javaguides.rpererv;

import java.util.Arrays;
import software.amazon.awscdk.Stack;
import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.services.ec2.SubnetConfiguration;
import software.amazon.awscdk.services.ec2.SubnetType;
import software.amazon.awscdk.services.ec2.Vpc;
import software.amazon.awscdk.services.ec2.VpcProps;
import software.constructs.Construct;

/**
 * VpcStack
 * <p>
 * Created by IntelliJ, Spring Framework Guru.
 *
 * @author architecture - pvraul
 * @version 14/02/2026 - 11:54
 * @since 1.17
 */
public class VpcStack extends Stack {

    private final Vpc vpc;

    public VpcStack(final Construct scope, final String id, final StackProps props) {
        super(scope, id, props);

        this.vpc = new Vpc(this, "Vpc", VpcProps.builder()
                .vpcName("MicroservicesVPC") // name of the VPC, same as the VPC name in the ECS cluster
                .maxAzs(2) // to create subnets in 2 availability zones for high availability
                // DO NOT DO THIS IN PRODUCTION, since it will create public subnets and expose the ECS cluster to the internet, but for this example we will use public subnets to avoid creating NAT gateways and keep the cost low
                // .natGateways(0)
                .natGateways(0) // Establecemos a 0 para evitar costos, ya que usaremos subredes públicas para este laboratorio.
                .subnetConfiguration(Arrays.asList(
                        SubnetConfiguration.builder()
                                .name("Public")
                                .subnetType(SubnetType.PUBLIC)
                                .cidrMask(24)
                                .build()
                ))
                .build());
    }

    public Vpc getVpc() {
        return vpc;
    }
}
