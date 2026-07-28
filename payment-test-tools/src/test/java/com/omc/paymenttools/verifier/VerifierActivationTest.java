package com.omc.paymenttools.verifier;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class VerifierActivationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withInitializer(context -> context.getBeanFactory()
                    .setConversionService(ApplicationConversionService.getSharedInstance()))
            .withUserConfiguration(VerifierTestConfiguration.class);

    @Test
    void enablesVerifierByDefault() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(VerifierEventConsumer.class);
            assertThat(context).hasSingleBean(ConsistencyVerifierController.class);
        });
    }

    @Test
    void disablesVerifierConsumerAndApiWhenConfigured() {
        contextRunner
                .withPropertyValues("payment-test-tools.verifier.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(VerifierEventConsumer.class);
                    assertThat(context).doesNotHaveBean(ConsistencyVerifierController.class);
                });
    }

    @Configuration(proxyBeanMethods = false)
    @Import({
            VerifierEventConsumer.class,
            ConsistencyVerifierController.class
    })
    static class VerifierTestConfiguration {

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }

        @Bean
        ObservedEventStore observedEventStore() {
            return new ObservedEventStore();
        }

        @Bean
        ConsistencyVerifierService consistencyVerifierService() {
            return mock(ConsistencyVerifierService.class);
        }
    }
}
