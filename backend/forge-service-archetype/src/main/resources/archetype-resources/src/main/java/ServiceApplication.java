#set( $dollar = '$' )
#set( $hash = '#' )
package ${package};

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

// 类名在生成时由 archetype-post-generate.groovy 按 serviceName 改写，如 orders-service → OrdersApplication。
@SpringBootApplication
public class ServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(ServiceApplication.class, args);
    }
}
