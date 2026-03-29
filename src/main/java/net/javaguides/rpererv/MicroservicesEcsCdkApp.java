package net.javaguides.rpererv;

import java.util.HashMap;
import java.util.Map;
import software.amazon.awscdk.App;
import software.amazon.awscdk.Environment;
import software.amazon.awscdk.StackProps;

public class MicroservicesEcsCdkApp {
    public static void main(final String[] args) {
        App app = new App();

        // Es buena práctica usar variables de entorno o valores quemados si el entorno es fijo
        final Environment environment = Environment.builder()
                .account(System.getenv("CDK_DEFAULT_ACCOUNT"))
                .region(System.getenv("CDK_DEFAULT_REGION"))
                .build();

        Map<String, String> infraestructureTags = new HashMap<>();
        infraestructureTags.put("team", "RperezvCode");
        infraestructureTags.put("cost", "MicroservicesInfraestructure");

        // 1. Repositorios ECR
        EcrStack ecrStack = new EcrStack(app, "MicroservicesEcr", StackProps.builder()
                .env(environment)
                .tags(infraestructureTags)
                .build());

        // 2. Red (VPC)
        VpcStack vpcStack = new VpcStack(app, "MicroservicesVpc", StackProps.builder()
                .env(environment)
                .tags(infraestructureTags)
                .build());

        // 3. Cluster ECS
        ClusterStack clusterStack = new ClusterStack(app, "MicroservicesCluster",
                StackProps.builder()
                        .env(environment)
                        .tags(infraestructureTags)
                        .build(),
                new ClusterStackProps(vpcStack.getVpc()));

        // 4. Servicio Fargate
        // Nota: El paso de ecrStack.getUsersMicroserviceRepository() crea una dependencia implícita
        new UsersMicroserviceStack(app, "UsersMicroserviceService",
                StackProps.builder()
                        .env(environment)
                        .tags(infraestructureTags)
                        .build(),
                new UsersServiceProps(
                        clusterStack.getCluster(),
                        ecrStack.getUsersMicroserviceRepository()
                ));

        // Generar el template de CloudFormation
        app.synth();
    }
}