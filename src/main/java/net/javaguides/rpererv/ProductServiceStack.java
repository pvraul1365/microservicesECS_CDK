package net.javaguides.rpererv;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import software.amazon.awscdk.Duration;
import software.amazon.awscdk.RemovalPolicy;
import software.amazon.awscdk.Stack;
import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.services.dynamodb.*;
import software.amazon.awscdk.services.ec2.Port;
import software.amazon.awscdk.services.ec2.SubnetSelection;
import software.amazon.awscdk.services.ec2.SubnetType;
import software.amazon.awscdk.services.ec2.Vpc;
import software.amazon.awscdk.services.ecr.Repository;
import software.amazon.awscdk.services.ecs.*;
import software.amazon.awscdk.services.elasticloadbalancingv2.AddApplicationTargetsProps;
import software.amazon.awscdk.services.elasticloadbalancingv2.ApplicationProtocol;
import software.amazon.awscdk.services.elasticloadbalancingv2.ApplicationTargetGroup;
import software.amazon.awscdk.services.elasticloadbalancingv2.HealthCheck;
import software.amazon.awscdk.services.iam.ManagedPolicy;
import software.amazon.awscdk.services.logs.LogGroup;
import software.amazon.awscdk.services.logs.LogGroupProps;
import software.amazon.awscdk.services.logs.RetentionDays;
import software.amazon.awscdk.services.sns.Topic;
import software.amazon.awscdk.services.sns.TopicProps;
import software.constructs.Construct;

/**
 * ProductServiceStack
 * <p>
 * Created by IntelliJ, Spring Framework Guru.
 *
 * @author architecture - pvraul
 * @version 15/02/2026 - 15:24
 * @since 1.17
 */
public class ProductServiceStack extends Stack {

    private final Topic productEventsTopic;

    public ProductServiceStack(final Construct scope, final String id, final StackProps props,
                               ProductServiceProps productServiceProps) {
        super(scope, id, props);

        this.productEventsTopic = new Topic(this, "ProductEventsTopic", TopicProps.builder()
                .displayName("Product events topic") // display name for the SNS topic, which will be shown in the AWS Management Console and can help to identify the purpose of the topic
                .topicName("product-events") // name of the SNS topic, which will be used in the application to publish events related to products (e.g., when a product is created, updated, or deleted)
                .build());

        Table productsDdb = new Table(this, "ProductsDdb", TableProps.builder()
                .partitionKey(Attribute.builder() // primary key for the DynamoDB table, which will be used to store the products data, and should be unique for each product
                        .name("id")
                        .type(AttributeType.STRING)
                        .build())
                .tableName("products") // name of the DynamoDB table, which will be used in the application to access the table
                .removalPolicy(RemovalPolicy.DESTROY) // to delete the table when the stack is deleted, for cleanup purposes
                .billingMode(BillingMode.PROVISIONED) // to use provisioned billing mode, which allows us to specify the read and write capacity units for the table, which is important for controlling costs and performance
                .readCapacity(1)
                .writeCapacity(1)
                .build());

        productsDdb.addGlobalSecondaryIndex(GlobalSecondaryIndexProps.builder()
                        .indexName("codeIdx") // name of the global secondary index, which will be used in the application to query products by their code
                        .partitionKey(Attribute.builder()
                                .name("code")
                                .type(AttributeType.STRING)
                                .build())
                        .projectionType(ProjectionType.KEYS_ONLY)
                        .readCapacity(1)
                        .writeCapacity(1)
                .build());

        // 1. Fargate Task Definition
        FargateTaskDefinition fargateTaskDefinition = new FargateTaskDefinition(this, "TaskDefinition",
                FargateTaskDefinitionProps.builder()
                        .family("products-service") // name of the task definition, same as the family name in the ECS service
                        .cpu(512) // 0.5 vCPU
                        .memoryLimitMiB(1024) // 1 GB RAM
                        // --- ESTO ES LO NUEVO ---
                        .runtimePlatform(RuntimePlatform.builder()
                                .cpuArchitecture(CpuArchitecture.ARM64) // <--- Idioma nativo M4
                                .operatingSystemFamily(OperatingSystemFamily.LINUX)
                                .build())
                        // ------------------------
                        .build());
        productsDdb.grantReadWriteData(fargateTaskDefinition.getTaskRole()); // to grant read permissions on the DynamoDB table to the Fargate task, which is necessary for the application inside the container to be able to read data from the table
        this.productEventsTopic.grantPublish(fargateTaskDefinition.getTaskRole());

        // 2. Log Driver for Container Definition
        AwsLogDriver logDriver = new AwsLogDriver(AwsLogDriverProps.builder()
                .logGroup(new LogGroup(this, "logGroup",
                        LogGroupProps.builder()
                                .logGroupName("ProductsService") // name of the log group, which will be used in CloudWatch Logs
                                .removalPolicy(RemovalPolicy.DESTROY) // to delete the log group when the stack is deleted, for cleanup purposes
                                .retention(RetentionDays.ONE_MONTH)
                                .build()
                        ))
                .streamPrefix("ProductsService") // prefix for the log stream, which will be used in CloudWatch Logs
                .build());

        // 3. Adding a container to the Fargate Task Definition (using the ECR repository created in the RepositoryStack)
        Map<String, String> envVariables = new HashMap<>();
        envVariables.put("SERVER_PORT", "8080"); // environment variable for the application inside the container, which will be used to configure the port where the application listens
        envVariables.put("AWS_PRODUCTSDDB_NAME", productsDdb.getTableName());
        envVariables.put("AWS_SNS_TOPIC_PRODUCT_EVENTS", this.productEventsTopic.getTopicArn());
        envVariables.put("AWS_REGION", this.getRegion());
        envVariables.put("AWS_XRAY_DAEMON_ADDRESS", "0.0.0.0:2000"); // to enable AWS X-Ray tracing for the application, which will allow us to monitor and troubleshoot the application in production, and to visualize the traces in the AWS X-Ray console
        envVariables.put("AWS_XRAY_CONTEXT_MISSING", "IGNORE_ERROR");
        envVariables.put("AWS_XRAY_TRACING_NAME", "productsservice"); // to set the name of the service in the AWS X-Ray console, which will help us to identify the traces from this
        envVariables.put("LOGGING_LEVEL_ROOT", "INFO"); // to set the logging level for the application, which will be used in the application to configure the logging behavior (e.g., to log only INFO level messages and above)

        fargateTaskDefinition.addContainer("ProductsServiceContainer", ContainerDefinitionOptions.builder()
                .image(ContainerImage.fromEcrRepository(productServiceProps.repository(), "1.8.0")) // to use the ECR repository created in the RepositoryStack
                        .containerName("productsService")
                .logging(logDriver) // to use the log driver created above for logging
                        .portMappings(Collections.singletonList(PortMapping.builder()
                                        .containerPort(8080) // port where the application inside the container listens, which will be used in the target group of the ALB
                                        .protocol(Protocol.TCP)
                                .build()))
                        .environment(envVariables)
                        .cpu(384)
                        .memoryLimitMiB(896)
                .build());

        fargateTaskDefinition.addContainer("xray", ContainerDefinitionOptions.builder()
                        .image(ContainerImage.fromRegistry("public.ecr.aws/xray/aws-xray-daemon:latest")) // to use the AWS X-Ray daemon container image from the public ECR registry, which will allow us to collect and send traces from the application to AWS X-Ray
                        .containerName("XRayProductsService")
                        .logging(new AwsLogDriver(AwsLogDriverProps.builder()
                                .logGroup(new LogGroup(this, "XRayLogGroup", LogGroupProps.builder()
                                        .logGroupName("XRayProductsService")
                                        .removalPolicy(RemovalPolicy.DESTROY)
                                        .retention(RetentionDays.ONE_MONTH)
                                        .build()))
                                .streamPrefix("XRayProductsService")
                                .build())) // to use the same log driver for the X-Ray daemon container
                        .portMappings(Collections.singletonList(PortMapping.builder()
                                        .containerPort(2000) // port where the X-Ray daemon listens for incoming traces
                                        .protocol(Protocol.UDP)
                                .build()))
                        .cpu(128)
                        .memoryLimitMiB(128)
                .build());
        fargateTaskDefinition.getTaskRole().addManagedPolicy(ManagedPolicy.fromAwsManagedPolicyName("AWSXrayWriteOnlyAccess")); // to grant permissions to the Fargate task role to write traces to AWS X-Ray, which is necessary for the application to be able to send traces to AWS X-Ray

        // 4. Create the Fargate Service and associate it with the ALB created in the NlbStack, using private subnets for security
        FargateService fargateService = FargateService.Builder.create(this, "ProductsService")
                .serviceName("ProductsService") // name of the ECS service, same as the service
                .cluster(productServiceProps.cluster())
                .taskDefinition(fargateTaskDefinition)
                .desiredCount(2)
                .assignPublicIp(false) // to not assign public IPs to the Fargate tasks, which is recommended for security reasons, since the ALB will be in charge of routing the traffic to the Fargate service and we don't want to expose the tasks directly to the internet
                .vpcSubnets(SubnetSelection.builder()
                        .subnetType(SubnetType.PRIVATE_WITH_EGRESS) // <--- Seguridad con salida a internet
                        .build())
                .build();
        productServiceProps.repository().grantPull(fargateTaskDefinition.getExecutionRole());
        fargateService.getConnections().allowFrom(productServiceProps.nlbStack().getApplicationLoadBalancer(), Port.tcp(8080)); // to allow incoming traffic from the ALB to the Fargate service on the port where the application listens (8080)

        // 4. Associate the Fargate Service with the ALB created in the NlbStack, using an Application Target Group with health checks and a short deregistration delay for faster recovery in case of failures
        ApplicationTargetGroup targetGroup = productServiceProps.nlbStack().getApplicationListener()
                .addTargets("ProdcutsServiceAlbTarget",
                        AddApplicationTargetsProps.builder()
                                .targetGroupName("productsServiceAlb")
                                .port(8080)
                                .protocol(ApplicationProtocol.HTTP)
                                .targets(Collections.singletonList(fargateService))
                                .deregistrationDelay(Duration.seconds(30)) // to reduce the time it takes for the ALB to stop routing traffic to unhealthy instances, which is important for faster recovery in case of failures
                                .healthCheck(HealthCheck.builder()
                                        .enabled(true)
                                        .interval(Duration.seconds(30)) // to set the interval between health checks, which is important for faster detection of unhealthy instances
                                        .timeout(Duration.seconds(10)) // to set the timeout for health checks, which is important for faster detection of unhealthy instances
                                        .path("/actuator/health") // to set the path for health checks, which should be an endpoint in your application that returns a 200 status code when the application is healthy, and a 500 status code when the application is unhealthy
                                        //.healthyHttpCodes("200") // to set the HTTP status codes that indicate a healthy instance, which should match the response from your health check endpoint
                                        .port("8080") // to set the port for health checks, which should match the container port where your application listens
                                        .build())
                                .build());
    }

    public Topic getProductEventsTopic() {
        return productEventsTopic;
    }

}

record ProductServiceProps(
        Vpc vpc,
        Cluster cluster,
        NlbStack nlbStack,
        Repository repository
) {
}
