package com.pigeon.blackbox;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;

/* Title and description shown at the top of Swagger UI */
@Configuration
class OpenApiConfiguration {

	@Bean
	OpenAPI blackboxOpenApi() {
		return new OpenAPI().info(new Info()
			.title("BoiteNoire API")
			.version("1.0")
			.description("""
					Analyses of the events of Pigeon, a professional messaging platform. Every analysis is \
					computed by a MongoDB aggregation pipeline; generate the data first with the generator \
					profile (see the README)."""));
	}
}
