package com.pigeon.blackbox.event;

import java.time.Duration;

import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationStartedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Component;

import com.mongodb.client.model.IndexOptions;

/*
 * Indexes of the events collection. Created at startup, and again by the generator once it has refilled
 * the collection. The choice of the fields and of their order is measured in docs/performance/README.md.
 */
@Component
public class EventIndexes {

	/*
	 * type first: equality ($match on one type or $in on a few);
	 * timestamp next: range of the period;
	 * userId last: not filtered, only read by $group, so the funnel and the top users never open a document.
	 */
	public static final String TYPE_TIMESTAMP_USER_ID = "type_timestamp_userId";

	private static final Logger log = LoggerFactory.getLogger(EventIndexes.class);

	private final MongoTemplate mongoTemplate;

	public EventIndexes(MongoTemplate mongoTemplate) {
		this.mongoTemplate = mongoTemplate;
	}

	/* Runs before the command-line runners: the explain profile measures with the index in place */
	@EventListener(ApplicationStartedEvent.class)
	public void createOnStartup() {
		ensureIndexes();
	}

	/* Same as db.events.createIndex({ type: 1, timestamp: 1, userId: 1 }, { name: ... }); does nothing if it exists */
	public void ensureIndexes() {
		long start = System.nanoTime();
		mongoTemplate.getCollection(mongoTemplate.getCollectionName(Event.class))
			.createIndex(new Document("type", 1).append("timestamp", 1).append("userId", 1),
					new IndexOptions().name(TYPE_TIMESTAMP_USER_ID));
		log.info("Index {} ready in {} ms", TYPE_TIMESTAMP_USER_ID,
				Duration.ofNanos(System.nanoTime() - start).toMillis());
	}
}
