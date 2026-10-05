package com.pigeon.blackbox.generator;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("generator")
public record GeneratorProperties(
		/* Same seed, same data: keeps the explain measurements reproducible */
		@DefaultValue("42") long seed,
		/* Simulated year, from January 1st to December 31st */
		@DefaultValue("2025") int year,
		@DefaultValue("3000") int users,
		/* Total number of events to produce; the brief requires at least 100 000 */
		@DefaultValue("300000") int events,
		/* Zipf exponent of the activity shares: the higher, the more activity goes to the biggest users */
		@DefaultValue("1.0") double zipfExponent,
		/* Number of events sent to MongoDB in a single insert */
		@DefaultValue("5000") int batchSize) {

	public GeneratorProperties {
		if (users < 1) {
			throw new IllegalArgumentException("generator.users must be at least 1, got: " + users);
		}
		if (events < users) {
			throw new IllegalArgumentException(
					"generator.events must be at least generator.users (" + users + "), got: " + events);
		}
		if (zipfExponent <= 0) {
			throw new IllegalArgumentException("generator.zipf-exponent must be positive, got: " + zipfExponent);
		}
		if (batchSize < 1) {
			throw new IllegalArgumentException("generator.batch-size must be at least 1, got: " + batchSize);
		}
	}
}
