package net.javaguides.rpererv;

import java.util.Collections;
import software.amazon.awscdk.Stack;
import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.aws_apigatewayv2_integrations.HttpNlbIntegration;
import software.amazon.awscdk.services.apigatewayv2.VpcLink;
import software.amazon.awscdk.services.apigatewayv2.VpcLinkProps;
import software.amazon.awscdk.services.ec2.*;
import software.amazon.awscdk.services.elasticloadbalancingv2.*;
import software.amazon.awscdk.services.elasticloadbalancingv2.targets.AlbListenerTarget;
import software.constructs.Construct;

/**
 * NlbStack
 * <p>
 * Created by IntelliJ, Spring Framework Guru.
 *
 * @author architecture - pvraul
 * @version 14/02/2026 - 14:44
 * @since 1.17
 */
public class NlbStack extends Stack {

    private final VpcLink vpcLink;
    private final NetworkLoadBalancer networkLoadBalancer;
    private final ApplicationLoadBalancer applicationLoadBalancer;
    private final ApplicationListener applicationListener; // Añadimos el listener del ALB
    private final HttpNlbIntegration nlbIntegration;

    public NlbStack(final Construct scope, final String id,
                    final StackProps props, NlbStackProps nlbStackProps) {
        super(scope, id, props);

        // 1. Crear el ALB (Privado)
        this.applicationLoadBalancer = new ApplicationLoadBalancer(this, "Alb", ApplicationLoadBalancerProps.builder()
                .loadBalancerName("ECommerceAlb")
                .vpc(nlbStackProps.vpc())
                .internetFacing(false)
                .build());
        this.applicationLoadBalancer.getConnections().allowFrom(
                Peer.ipv4(nlbStackProps.vpc().getVpcCidrBlock()),
                Port.tcp(80),
                "Permitir entrada desde la VPC (NLB)"
        );

        // 2. Crear el Listener del ALB (donde llegarán las rutas de los microservicios)
        this.applicationListener = this.applicationLoadBalancer.addListener("ProductsServiceAlbListener",
                BaseApplicationListenerProps.builder()
                        .port(80)
                        .open(true)
                        .build());

        // Respuesta por defecto 404 para el ALB
        this.applicationListener.addAction("DefaultAction", AddApplicationActionProps.builder()
                .action(ListenerAction.fixedResponse(404, FixedResponseOptions.builder()
                        .contentType("text/plain")
                        .messageBody("E-Commerce Service Not Found")
                        .build()))
                .build());

        // 3. Crear el NLB
        this.networkLoadBalancer = new NetworkLoadBalancer(this, "Nlb", NetworkLoadBalancerProps.builder()
                .loadBalancerName("ECommerceNlb")
                .vpc(nlbStackProps.vpc())
                .internetFacing(false)
                // FORZAMOS A QUE ESTÉ EN LAS MISMAS QUE EL VPC LINK
                .vpcSubnets(SubnetSelection.builder()
                        .subnetType(SubnetType.PRIVATE_ISOLATED)
                        .build())
                .build());

        // 4. ENCADENAMIENTO: NLB -> ALB (Usando AlbListenerTarget para evitar el warning)
        NetworkListener nlbListener = this.networkLoadBalancer.addListener("NlbListener",
                BaseNetworkListenerProps.builder()
                        .port(80)
                        .build());

        nlbListener.addTargets("AlbTarget", AddNetworkTargetsProps.builder()
                .port(80)
                // CAMBIO AQUÍ: Usamos el listener que creamos en el paso 2
                .targets(Collections.singletonList(new AlbListenerTarget(this.applicationListener)))
                .healthCheck(HealthCheck.builder()
                        .port("80")
                        .healthyHttpCodes("200-499") // <--- ACEPTAR 404 COMO SALUDABLE
                        .build())
                .build());

        // 5.1. Crea un Security Group para el VpcLink (dentro del constructor de NlbStack)
        SecurityGroup vpcLinkSg = SecurityGroup.Builder.create(this, "VpcLinkSg")
                .vpc(nlbStackProps.vpc())
                .allowAllOutbound(true)
                .description("SG para el VpcLink de API Gateway")
                .build();
        // 5.2 Crear el VpcLink para API Gateway especificando subredes ISOLATED
        this.vpcLink = new VpcLink(this, "VpcLink", VpcLinkProps.builder()
                .vpcLinkName("ECommerceVpcLink")
                .vpc(nlbStackProps.vpc())
                // AÑADE ESTO:
                .securityGroups(Collections.singletonList(vpcLinkSg))
                .subnets(SubnetSelection.builder()
                        .subnetType(SubnetType.PRIVATE_ISOLATED)
                        .build())
                .build());

        // 6. Integración para API Gateway
        this.nlbIntegration = HttpNlbIntegration.Builder.create("NlbIntegration", nlbListener)
                .vpcLink(this.vpcLink)
                .build();
    }

    // Getters
    public VpcLink getVpcLink() { return vpcLink; }
    public NetworkLoadBalancer getNetworkLoadBalancer() { return networkLoadBalancer; }
    public ApplicationLoadBalancer getApplicationLoadBalancer() { return applicationLoadBalancer; }
    public ApplicationListener getApplicationListener() { return applicationListener; }
    public HttpNlbIntegration getNlbIntegration() { return nlbIntegration; }
}

record NlbStackProps(
        Vpc vpc
) {
}
