package net.javaguides.rpererv;

import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import software.amazon.awscdk.Stack;
import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.services.ec2.*;
import software.amazon.awscdk.services.ecr.Repository;
import software.amazon.awscdk.services.ecs.*;
import software.amazon.awscdk.services.ecs.Protocol;
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

        // 2. Añadir el Contenedor
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

        // 3. Crear Security Group
        SecurityGroup sg = new SecurityGroup(this, "UsersServiceSG", SecurityGroupProps.builder()
                .vpc(serviceProps.cluster().getVpc())
                .securityGroupName("users-microservice-ecs-service-sg")
                .description("Users Microservices ECS Security Group")
                .allowAllOutbound(true)
                .build());

        sg.addIngressRule(Peer.anyIpv4(), Port.tcp(8081), "Allow HTTP access on 8081");

        // 4. Crear el Servicio Fargate
        new FargateService(this, "UsersFargateService", FargateServiceProps.builder()
                .cluster(serviceProps.cluster())
                .vpcSubnets(SubnetSelection.builder()
                        .subnetType(SubnetType.PUBLIC)
                        .build())
                .serviceName("users-microservice-task-definition-service")
                .taskDefinition(taskDefinition)
                .desiredCount(1)
                .assignPublicIp(true) // Requerido para acceder desde internet
                .securityGroups(Collections.singletonList(sg))
                .build());
    }

}

record UsersServiceProps(Cluster cluster, Repository repository) {}