package net.javaguides.rpererv;

import java.util.Arrays;
import java.util.Map;
import software.amazon.awscdk.Duration;
import software.amazon.awscdk.Stack;
import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.services.ecr.Repository;

import software.amazon.awscdk.services.ecs.AwsLogDriverProps;
import software.amazon.awscdk.services.ecs.Cluster;
import software.amazon.awscdk.services.ecs.ContainerDefinition;
import software.amazon.awscdk.services.ecs.ContainerDefinitionOptions;
import software.amazon.awscdk.services.ecs.ContainerImage;
import software.amazon.awscdk.services.ecs.CpuArchitecture;
import software.amazon.awscdk.services.ecs.FargateTaskDefinition;
import software.amazon.awscdk.services.ecs.FargateTaskDefinitionProps;
import software.amazon.awscdk.services.ecs.LogDriver;
import software.amazon.awscdk.services.ecs.OperatingSystemFamily;
import software.amazon.awscdk.services.ecs.PortMapping;
import software.amazon.awscdk.services.ecs.Protocol;
import software.amazon.awscdk.services.ecs.RuntimePlatform;
import software.amazon.awscdk.services.ecs.patterns.ApplicationLoadBalancedFargateService;
import software.amazon.awscdk.services.ecs.patterns.ApplicationLoadBalancedFargateServiceProps;
import software.amazon.awscdk.services.elasticloadbalancingv2.HealthCheck;
import software.amazon.awscdk.services.iam.PolicyStatement;
import software.amazon.awscdk.services.logs.RetentionDays;
import software.constructs.Construct;

/**
 * UsersMicroserviceStack
 * <p>
 * Created by IntelliJ, Spring Framework Guru.
 *
 * @author architecture - pvraul
 * @version 28/03/2026 - 11:22
 * @since 1.17
 */
public class UsersMicroserviceStack extends Stack {

    public UsersMicroserviceStack(final Construct scope, final String id, final StackProps props,
                                  UsersServiceProps serviceProps) {
        super(scope, id, props);

        // 1. Definición de la Tarea
        FargateTaskDefinition taskDefinition = new FargateTaskDefinition(this, "UsersTaskDef",
                FargateTaskDefinitionProps.builder()
                        .family("users-microservice-task-definition")
                        .cpu(512)    // 0.5 vCPU
                        .memoryLimitMiB(1024) // 1 GB
                        .runtimePlatform(RuntimePlatform.builder()
                                .cpuArchitecture(CpuArchitecture.X86_64)
                                .operatingSystemFamily(OperatingSystemFamily.LINUX)
                                .build())
                        .build());

        // Esto asegura que la tarea tenga permiso para hacer 'Pull' de la imagen en ECR
        taskDefinition.addToExecutionRolePolicy(PolicyStatement.Builder.create()
                .actions(Arrays.asList("ecr:GetAuthorizationToken", "ecr:BatchCheckLayerAvailability", "ecr:GetDownloadUrlForLayer", "ecr:BatchGetImage"))
                .resources(Arrays.asList("*"))
                .build());

        // 2. Añadir el Contenedor (Puerto 8081 para Spring Boot)
        ContainerDefinition container = taskDefinition.addContainer("UsersContainer",
                ContainerDefinitionOptions.builder()
                        .containerName("users-microservice")
                        .image(ContainerImage.fromEcrRepository(serviceProps.repository()))
                        .environment(Map.of("spring.profiles.active", "dev"))
                        .logging(LogDriver.awsLogs(AwsLogDriverProps.builder()
                                .logRetention(RetentionDays.ONE_DAY)
                                .streamPrefix("users-microservice")
                                .build()))
                        .build());

        container.addPortMappings(PortMapping.builder()
                .containerPort(8081)
                .protocol(Protocol.TCP)
                .build());

        // 3. Crear el Servicio Balanceado (El "Pattern")
        ApplicationLoadBalancedFargateService albService = new ApplicationLoadBalancedFargateService(this, "UsersALBService",
                ApplicationLoadBalancedFargateServiceProps.builder()
                        .cluster(serviceProps.cluster())           // Tu cluster existente
                        .serviceName("users-microservice-lb-service")
                        .loadBalancerName("users-microservice-lb") // Nombre del ALB
                        .cpu(512)
                        .memoryLimitMiB(1024)
                        .desiredCount(2)                           // ¡2 Instancias levantadas!
                        .taskDefinition(taskDefinition)
                        .publicLoadBalancer(true)                  // Acceso desde internet
                        .assignPublicIp(true)                      // Necesario al no tener NAT Gateway
                        .build());

        // 4. Configurar el Health Check (Actuator)
        albService.getTargetGroup().configureHealthCheck(
                HealthCheck.builder()
                        .path("/actuator/health")
                        .port("8081")
                        .protocol(software.amazon.awscdk.services.elasticloadbalancingv2.Protocol.HTTP)
                        .healthyThresholdCount(2)
                        .unhealthyThresholdCount(5)
                        .interval(Duration.seconds(30))
                        .build()
        );
    }

}

record UsersServiceProps(Cluster cluster, Repository repository) {}