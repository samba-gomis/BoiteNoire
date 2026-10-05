package com.pigeon.blackbox.generator;

import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Profile;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Component;

import com.pigeon.blackbox.event.Event;
import com.pigeon.blackbox.event.EventType;
import com.pigeon.blackbox.user.User;

/* Entry point of the generator: runs only with the "generator" Spring profile */
@Component
@Profile("generator")
@EnableConfigurationProperties(GeneratorProperties.class)
class DataGenerator implements CommandLineRunner {

	private static final Logger log = LoggerFactory.getLogger(DataGenerator.class);

	private final MongoTemplate mongoTemplate;
	private final GeneratorProperties properties;

	DataGenerator(MongoTemplate mongoTemplate, GeneratorProperties properties) {
		this.mongoTemplate = mongoTemplate;
		this.properties = properties;
	}

	@Override
	public void run(String... args) {
		long start = System.nanoTime();
		log.info("Generating {} events for {} users over {} (seed {})", properties.events(), properties.users(),
				properties.year(), properties.seed());

		/* Starting from empty collections makes the generator safe to run again */
		mongoTemplate.dropCollection(Event.class);
		mongoTemplate.dropCollection(User.class);

		/* Events are inserted batch by batch as they are produced: the whole year never sits in memory */
		List<Event> batch = new ArrayList<>(properties.batchSize());
		Map<EventType, Long> countsByType = new EnumMap<>(EventType.class);
		Simulation.Result result = Simulation.run(properties, event -> {
			countsByType.merge(event.type(), 1L, (count, one) -> count + one);
			batch.add(event);
			if (batch.size() == properties.batchSize()) {
				insert(batch);
			}
		});
		insert(batch);
		mongoTemplate.insert(result.users().stream().map(user -> user.toUser()).toList(), User.class);

		long payingUsers = result.users().stream().filter(user -> user.subscription() != null).count();
		log.info("Inserted {} users, {} of them paying", result.users().size(), payingUsers);
		log.info("Inserted {} events: {} funnel events and {} daily activity events", result.totalEvents(),
				result.funnelEvents(), result.activityEvents());
		countsByType.forEach((type, count) -> log.info("  {} {}", type, count));
		log.info("Incident days: {}", result.incidentDays());
		log.info("Done in {} s", Duration.ofNanos(System.nanoTime() - start).toSeconds());
	}

	private void insert(List<Event> batch) {
		if (!batch.isEmpty()) {
			mongoTemplate.insert(batch, Event.class);
			batch.clear();
		}
	}
}
