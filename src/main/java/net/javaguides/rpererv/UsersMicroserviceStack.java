package net.javaguides.rpererv;

import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import software.amazon.awscdk.CfnOutput;
import software.amazon.awscdk.Stack;
import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.services.ec2.Peer;
import software.amazon.awscdk.services.ec2.Port;
import software.amazon.awscdk.services.ec2.SecurityGroup;
import software.amazon.awscdk.services.ec2.SecurityGroupProps;
import software.amazon.awscdk.services.ec2.SubnetSelection;
import software.amazon.awscdk.services.ec2.SubnetType;
import software.amazon.awscdk.services.ecr.Repository;
import software.amazon.awscdk.services.ecs.AwsLogDriverProps;
import software.amazon.awscdk.services.ecs.Cluster;
import software.amazon.awscdk.services.ecs.ContainerDefinition;
import software.amazon.awscdk.services.ecs.ContainerDefinitionOptions;
import software.amazon.awscdk.services.ecs.ContainerImage;
import software.amazon.awscdk.services.ecs.CpuArchitecture;
import software.amazon.awscdk.services.ecs.FargateService;
import software.amazon.awscdk.services.ecs.FargateServiceProps;
import software.amazon.awscdk.services.ecs.FargateTaskDefinition;
import software.amazon.awscdk.services.ecs.FargateTaskDefinitionProps;
import software.amazon.awscdk.services.ecs.LogDriver;
import software.amazon.awscdk.services.ecs.OperatingSystemFamily;
import software.amazon.awscdk.services.ecs.PortMapping;
import software.amazon.awscdk.services.ecs.Protocol;
import software.amazon.awscdk.services.ecs.RuntimePlatform;
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

        // 🚨 IMPORTANTE: Añade permisos para crear logs en CloudWatch
        taskDefinition.addToExecutionRolePolicy(PolicyStatement.Builder.create()
                .actions(Arrays.asList("logs:CreateLogStream", "logs:PutLogEvents"))
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
        FargateService usersFargateService = new FargateService(this, "UsersFargateService", FargateServiceProps.builder()
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

        // 5. Outputs para facilitarte la vida en tus pruebas
        CfnOutput.Builder.create(this, "ClusterNameOutput")
                .value(serviceProps.cluster().getClusterName())
                .description("Nombre del cluster para buscar la tarea")
                .build();

        CfnOutput.Builder.create(this, "ServiceNameOutput")
                .value(usersFargateService.getServiceName())
                .description("Nombre del servicio Fargate")
                .build();
    }

}

record UsersServiceProps(Cluster cluster, Repository repository) {}