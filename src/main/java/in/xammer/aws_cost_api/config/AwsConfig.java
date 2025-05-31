package in.xammer.aws_cost_api.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.costexplorer.CostExplorerClient;
import software.amazon.awssdk.services.organizations.OrganizationsClient;

@Configuration
public class AwsConfig {

    @Bean
    public CostExplorerClient costExplorerClient() {
        // The SDK will automatically use credentials from the environment
        // The Cost Explorer API endpoint is global (us-east-1)
        return CostExplorerClient.builder()
            .region(Region.US_EAST_1)
            .build();
    }

    @Bean
    public OrganizationsClient organizationsClient() {
        // The Organizations API endpoint is also global (us-east-1)
        return OrganizationsClient.builder()
            .region(Region.US_EAST_1)
            .build();
    }
}
