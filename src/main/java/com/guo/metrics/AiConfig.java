package com.guo.metrics;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.model.tool.DefaultToolCallingManager;
import org.springframework.ai.openai.*;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.tool.execution.DefaultToolExecutionExceptionProcessor;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.web.client.RestClient;

@Configuration
@ConditionalOnProperty(name = "app.ai.enabled", havingValue = "true")
public class AiConfig {
  @Bean
  OpenAiApi openAiApi(
      @Value("${app.ai.api-key}") String key, @Value("${app.ai.base-url}") String url) {
    if (key.isBlank()) throw new IllegalArgumentException("AI_ENABLED=true 时必须设置 AI_API_KEY");
    var factory = new SimpleClientHttpRequestFactory();
    factory.setConnectTimeout(10000);
    factory.setReadTimeout(30000);
    return OpenAiApi.builder()
        .apiKey(key)
        .baseUrl(url)
        .restClientBuilder(RestClient.builder().requestFactory(factory))
        .build();
  }

  @Bean
  ChatClient chatClient(OpenAiApi api, @Value("${app.ai.model}") String model) {
    var chat =
        OpenAiChatModel.builder()
            .openAiApi(api)
            .toolCallingManager(
                DefaultToolCallingManager.builder()
                    .toolExecutionExceptionProcessor(
                        DefaultToolExecutionExceptionProcessor.builder().alwaysThrow(true).build())
                    .build())
            .defaultOptions(
                OpenAiChatOptions.builder().model(model).temperature(0.1).maxTokens(1200).build())
            .retryTemplate(RetryTemplate.builder().maxAttempts(1).fixedBackoff(100).build())
            .build();
    return ChatClient.create(chat);
  }
}
