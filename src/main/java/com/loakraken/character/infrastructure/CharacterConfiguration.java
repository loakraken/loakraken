package com.loakraken.character.infrastructure;

import com.loakraken.character.application.CharacterSnapshotPolicy;
import java.net.http.HttpClient;
import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({LostArkProperties.class, CharacterSnapshotPolicy.class})
class CharacterConfiguration {
  @Bean
  Clock characterClock() {
    return Clock.systemUTC();
  }

  @Bean
  RestClient lostArkRestClient(LostArkProperties properties) {
    var client =
        HttpClient.newBuilder()
            .connectTimeout(properties.connectTimeout())
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    var factory = new JdkClientHttpRequestFactory(client);
    factory.setReadTimeout(properties.readTimeout());
    return RestClient.builder()
        .baseUrl(properties.baseUrl().toString())
        .requestFactory(factory)
        .defaultHeader("Accept", "application/json")
        .defaultHeaders(headers -> headers.setBearerAuth(properties.apiKey()))
        .build();
  }
}
