package net.javaguides.rpererv;

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
 * @version 29/03/2026 - 19:41
 * @since 1.17
 */
public class AlbumsDatabaseStack extends Stack {

    private final DatabaseInstance database;
    private final SecurityGroup dbSg;

    public AlbumsDatabaseStack(final Construct scope, final String id, final StackProps props, Vpc vpc) {
        super(scope, id, props);

        // 1. Security Group específico para Albums
        this.dbSg = SecurityGroup.Builder.create(this, "AlbumsDatabaseSG")
                .vpc(vpc)
                .securityGroupName("albums-microservice-rds-db-sg")
                .allowAllOutbound(true)
                .build();

        // 2. Instancia RDS MySQL para Albums
        this.database = DatabaseInstance.Builder.create(this, "AlbumsMySQLInstance")
                .engine(DatabaseInstanceEngine.mysql(MySqlInstanceEngineProps.builder()
                        .version(MysqlEngineVersion.VER_8_0)
                        .build()))
                .instanceType(InstanceType.of(InstanceClass.BURSTABLE3, InstanceSize.MICRO))
                .vpc(vpc)
                .vpcSubnets(SubnetSelection.builder()
                        .subnetType(SubnetType.PRIVATE_ISOLATED)
                        .build())
                .securityGroups(java.util.Collections.singletonList(this.dbSg))
                .instanceIdentifier("photo-api-albums-db") // Identificador solicitado
                .databaseName("albums")                    // Nombre DB solicitado
                .credentials(Credentials.fromPassword("admin",
                        software.amazon.awscdk.SecretValue.unsafePlainText("fysgeS-ruzfik-2tyrhu")))
                .port(3306)
                .multiAz(false)
                .allocatedStorage(20)
                .backupRetention(Duration.days(0)) // No enable automated backups
                .publiclyAccessible(false)
                .removalPolicy(RemovalPolicy.DESTROY)
                .build();
    }

    public DatabaseInstance getDatabase() { return database; }
    public SecurityGroup getDbSecurityGroup() { return dbSg; }

}
