package com.pigeon.blackbox.performance;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("explain")
public record ExplainProperties(
		/* Name of the measure, e.g. before or after: the report goes to explain-<label>.json */
		String label,
		/* Each pipeline is measured this many times and the median is kept */
		@DefaultValue("5") int runs,
		@DefaultValue("docs/performance") String outputDirectory) {

	public ExplainProperties {
		if (label == null || !label.matches("[a-z0-9-]+")) {
			throw new IllegalArgumentException(
					"explain.label is required, in lowercase letters, digits and dashes (e.g. before), got: " + label);
		}
		if (runs < 1) {
			throw new IllegalArgumentException("explain.runs must be at least 1, got: " + runs);
		}
	}
}
