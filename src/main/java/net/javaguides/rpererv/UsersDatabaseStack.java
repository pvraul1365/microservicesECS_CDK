package net.javaguides.rpererv;

import software.amazon.awscdk.CfnOutput;
import software.amazon.awscdk.Duration;
import software.amazon.awscdk.RemovalPolicy;
import software.amazon.awscdk.Stack;
import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.services.ec2.InstanceClass;
import software.amazon.awscdk.services.ec2.InstanceSize;
import software.amazon.awscdk.services.ec2.InstanceType;
import software.amazon.awscdk.services.ec2.SecurityGroup;
import software.amazon.awscdk.services.ec2.SubnetSelection;
import software.amazon.awscdk.services.ec2.SubnetType;
import software.amazon.awscdk.services.ec2.Vpc;
import software.amazon.awscdk.services.rds.Credentials;
import software.amazon.awscdk.services.rds.DatabaseInstance;
import software.amazon.awscdk.services.rds.DatabaseInstanceEngine;
import software.amazon.awscdk.services.rds.MySqlInstanceEngineProps;
import software.amazon.awscdk.services.rds.MysqlEngineVersion;
import software.constructs.Construct;

/**
 * DatabaseStack
 * <p>
 * Created by IntelliJ, Spring Framework Guru.
 *
 * @author architecture - pvraul
 * @version 29/03/2026 - 16:51
 * @since 1.17
 */
public class UsersDatabaseStack extends Stack {
    private final DatabaseInstance database;
    private final SecurityGroup dbSg;

    public UsersDatabaseStack(final Construct scope, final String id, final StackProps props, Vpc vpc) {
        super(scope, id, props);

        // 1. Security Group para la DB
        this.dbSg = SecurityGroup.Builder.create(this, "DatabaseSG")
                .vpc(vpc)
                .securityGroupName("users-microservice-rds-db-sg")
                .allowAllOutbound(true)
                .build();

        // 2. Instancia RDS MySQL
        this.database = DatabaseInstance.Builder.create(this, "MySQLInstance")
                .engine(DatabaseInstanceEngine.mysql(MySqlInstanceEngineProps.builder()
                        .version(MysqlEngineVersion.VER_8_0)
                        .build()))
                .instanceType(InstanceType.of(InstanceClass.BURSTABLE3, InstanceSize.MICRO)) // db.t3.micro
                .vpc(vpc)
                .vpcSubnets(SubnetSelection.builder()
                        .subnetType(SubnetType.PRIVATE_ISOLATED) // <--- Crucial: que coincida con tu VpcStack
                        .build())
                .securityGroups(java.util.Collections.singletonList(this.dbSg))
                .instanceIdentifier("users")
                .databaseName("users")
                .credentials(Credentials.fromPassword("admin",
                        software.amazon.awscdk.SecretValue.unsafePlainText("zugqIn-tuzxot-juxki0")))
                .port(3306)
                .multiAz(false) // Para asegurar Free Tier
                .allocatedStorage(20)
                .backupRetention(Duration.days(0)) // Sin backups automáticos por ahora
                .publiclyAccessible(false) // Acceso privado
                .removalPolicy(RemovalPolicy.DESTROY) // ¡CUIDADO! Cambiar a RETAIN en producción
                .build();

        CfnOutput.Builder.create(this, "RDSHost")
                .value(this.database.getDbInstanceEndpointAddress())
                .description("Host de la base de datos MySQL (solo accesible internamente)")
                .build();
    }

    public DatabaseInstance getDatabase() { return database; }
    public SecurityGroup getDbSg() { return dbSg; }
}
