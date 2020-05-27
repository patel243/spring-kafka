/*
 * Copyright 2018-2020 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.kafka.listener;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiFunction;

import org.apache.commons.logging.LogFactory;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.Headers;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.apache.kafka.common.header.internals.RecordHeaders;

import org.springframework.core.log.LogAccessor;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.kafka.support.serializer.DeserializationException;
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer;
import org.springframework.lang.Nullable;
import org.springframework.util.Assert;
import org.springframework.util.ObjectUtils;

/**
 * A {@link ConsumerRecordRecoverer} that publishes a failed record to a dead-letter
 * topic.
 *
 * @author Gary Russell
 * @since 2.2
 *
 */
public class DeadLetterPublishingRecoverer implements ConsumerRecordRecoverer {

	private static final LogAccessor LOGGER =
			new LogAccessor(LogFactory.getLog(DeadLetterPublishingRecoverer.class));

	private static final BiFunction<ConsumerRecord<?, ?>, Exception, TopicPartition>
		DEFAULT_DESTINATION_RESOLVER = (cr, e) -> new TopicPartition(cr.topic() + ".DLT", cr.partition());

	private final KafkaOperations<Object, Object> template;

	private final Map<Class<?>, KafkaOperations<?, ?>> templates;

	private final boolean transactional;

	private final BiFunction<ConsumerRecord<?, ?>, Exception, TopicPartition> destinationResolver;

	private boolean retainExceptionHeader;

	/**
	 * Create an instance with the provided template and a default destination resolving
	 * function that returns a TopicPartition based on the original topic (appended with ".DLT")
	 * from the failed record, and the same partition as the failed record. Therefore the
	 * dead-letter topic must have at least as many partitions as the original topic.
	 * @param template the {@link KafkaOperations} to use for publishing.
	 */
	public DeadLetterPublishingRecoverer(KafkaOperations<? extends Object, ? extends Object> template) {
		this(template, DEFAULT_DESTINATION_RESOLVER);
	}

	/**
	 * Create an instance with the provided template and destination resolving function,
	 * that receives the failed consumer record and the exception and returns a
	 * {@link TopicPartition}. If the partition in the {@link TopicPartition} is less than
	 * 0, no partition is set when publishing to the topic.
	 * @param template the {@link KafkaOperations} to use for publishing.
	 * @param destinationResolver the resolving function.
	 */
	public DeadLetterPublishingRecoverer(KafkaOperations<? extends Object, ? extends Object> template,
			BiFunction<ConsumerRecord<?, ?>, Exception, TopicPartition> destinationResolver) {
		this(Collections.singletonMap(Object.class, template), destinationResolver);
	}

	/**
	 * Create an instance with the provided template and a default destination resolving
	 * function that returns a TopicPartition based on the original topic (appended with ".DLT")
	 * from the failed record, and the same partition as the failed record. Therefore the
	 * dead-letter topic must have at least as many partitions as the original topic.
	 * @param template the {@link KafkaTemplate} to use for publishing.
	 * @deprecated in favor of {@link #DeadLetterPublishingRecoverer(KafkaOperations)}.
	 */
	@Deprecated
	public DeadLetterPublishingRecoverer(KafkaTemplate<? extends Object, ? extends Object> template) {
		this(template, DEFAULT_DESTINATION_RESOLVER);
	}

	/**
	 * Create an instance with the provided template and destination resolving function,
	 * that receives the failed consumer record and the exception and returns a
	 * {@link TopicPartition}. If the partition in the {@link TopicPartition} is less than
	 * 0, no partition is set when publishing to the topic.
	 * @param template the {@link KafkaOperations} to use for publishing.
	 * @param destinationResolver the resolving function.
	 * @deprecated in favor of {@link #DeadLetterPublishingRecoverer(KafkaOperations, BiFunction)}.
	 */
	@Deprecated
	public DeadLetterPublishingRecoverer(KafkaTemplate<? extends Object, ? extends Object> template,
			BiFunction<ConsumerRecord<?, ?>, Exception, TopicPartition> destinationResolver) {
		this(Collections.singletonMap(Object.class, template), destinationResolver);
	}

	/**
	 * Create an instance with the provided templates and a default destination resolving
	 * function that returns a TopicPartition based on the original topic (appended with
	 * ".DLT") from the failed record, and the same partition as the failed record.
	 * Therefore the dead-letter topic must have at least as many partitions as the
	 * original topic. The templates map keys are classes and the value the corresponding
	 * template to use for objects (producer record values) of that type. A
	 * {@link java.util.LinkedHashMap} is recommended when there is more than one
	 * template, to ensure the map is traversed in order.
	 * @param templates the {@link KafkaOperations}s to use for publishing.
	 */
	public DeadLetterPublishingRecoverer(Map<Class<?>, KafkaOperations<? extends Object, ? extends Object>> templates) {
		this(templates, DEFAULT_DESTINATION_RESOLVER);
	}

	/**
	 * Create an instance with the provided templates and destination resolving function,
	 * that receives the failed consumer record and the exception and returns a
	 * {@link TopicPartition}. If the partition in the {@link TopicPartition} is less than
	 * 0, no partition is set when publishing to the topic. The templates map keys are
	 * classes and the value the corresponding template to use for objects (producer
	 * record values) of that type. A {@link java.util.LinkedHashMap} is recommended when
	 * there is more than one template, to ensure the map is traversed in order.
	 * @param templates the {@link KafkaOperations}s to use for publishing.
	 * @param destinationResolver the resolving function.
	 */
	@SuppressWarnings("unchecked")
	public DeadLetterPublishingRecoverer(Map<Class<?>, KafkaOperations<? extends Object, ? extends Object>> templates,
			BiFunction<ConsumerRecord<?, ?>, Exception, TopicPartition> destinationResolver) {

		Assert.isTrue(!ObjectUtils.isEmpty(templates), "At least one template is required");
		Assert.notNull(destinationResolver, "The destinationResolver cannot be null");
		this.template = templates.size() == 1
				? (KafkaOperations<Object, Object>) templates.values().iterator().next()
				: null;
		this.templates = templates;
		this.transactional = templates.values().iterator().next().isTransactional();
		Boolean tx = this.transactional;
		Assert.isTrue(templates.values()
			.stream()
			.map(t -> t.isTransactional())
			.allMatch(t -> t.equals(tx)), "All templates must have the same setting for transactional");
		this.destinationResolver = destinationResolver;
	}

	/**
	 * Set to true to retain a Java serialized {@link DeserializationException} header. By
	 * default, such headers are removed from the published record, unless both key and
	 * value deserialization exceptions occur, in which case, the DLT_* headers are
	 * created from the value exception and the key exception header is retained.
	 * @param retainExceptionHeader true to retain the
	 * @since 2.5
	 */
	public void setRetainExceptionHeader(boolean retainExceptionHeader) {
		this.retainExceptionHeader = retainExceptionHeader;
	}

	@Override
	public void accept(ConsumerRecord<?, ?> record, Exception exception) {
		TopicPartition tp = this.destinationResolver.apply(record, exception);
		boolean isKey = false;
		DeserializationException deserEx = ListenerUtils.getExceptionFromHeader(record,
				ErrorHandlingDeserializer.VALUE_DESERIALIZER_EXCEPTION_HEADER, LOGGER);
		if (deserEx == null) {
			deserEx = ListenerUtils.getExceptionFromHeader(record,
					ErrorHandlingDeserializer.KEY_DESERIALIZER_EXCEPTION_HEADER, LOGGER);
			isKey = true;
		}
		Headers headers;
		if (deserEx == null || this.retainExceptionHeader) {
			headers = new RecordHeaders(record.headers().toArray());
		}
		else {
			headers = deserEx.getHeaders();
		}
		enhanceHeaders(headers, record, exception); // NOSONAR headers are never null
		ProducerRecord<Object, Object> outRecord = createProducerRecord(record, tp, headers,
				deserEx == null ? null : deserEx.getData(), isKey);
		KafkaOperations<Object, Object> kafkaTemplate = findTemplateForValue(outRecord.value());
		if (this.transactional && !kafkaTemplate.inTransaction() && !kafkaTemplate.isAllowNonTransactional()) {
			kafkaTemplate.executeInTransaction(t -> {
				publish(outRecord, t);
				return null;
			});
		}
		else {
			publish(outRecord, kafkaTemplate);
		}
	}

	@SuppressWarnings("unchecked")
	private KafkaOperations<Object, Object> findTemplateForValue(Object value) {
		if (this.template != null) {
			return this.template;
		}
		Optional<Class<?>> key = this.templates.keySet()
			.stream()
			.filter((k) -> k.isAssignableFrom(value.getClass()))
			.findFirst();
		if (key.isPresent()) {
			return (KafkaOperations<Object, Object>) this.templates.get(key.get());
		}
		LOGGER.warn(() -> "Failed to find a template for " + value.getClass() + " attempting to use the last entry");
		return (KafkaOperations<Object, Object>) this.templates.values()
				.stream()
				.reduce((first,  second) -> second)
				.get();
	}

	/**
	 * Subclasses can override this method to customize the producer record to send to the
	 * DLQ. The default implementation simply copies the key and value from the consumer
	 * record and adds the headers. The timestamp is not set (the original timestamp is in
	 * one of the headers). IMPORTANT: if the partition in the {@link TopicPartition} is
	 * less than 0, it must be set to null in the {@link ProducerRecord}.
	 * @param record the failed record
	 * @param topicPartition the {@link TopicPartition} returned by the destination
	 * resolver.
	 * @param headers the headers - original record headers plus DLT headers.
	 * @param data the value to use instead of the consumer record value.
	 * @param isKey true if key deserialization failed.
	 * @return the producer record to send.
	 * @see KafkaHeaders
	 */
	protected ProducerRecord<Object, Object> createProducerRecord(ConsumerRecord<?, ?> record,
			TopicPartition topicPartition, Headers headers, @Nullable byte[] data, boolean isKey) {

		return new ProducerRecord<>(topicPartition.topic(),
				topicPartition.partition() < 0 ? null : topicPartition.partition(),
				isKey && data != null ? data : record.key(),
				data == null || isKey ? record.value() : data, headers);
	}

	/**
	 * Override this if you want more than just logging of the send result.
	 * @param outRecord the record to send.
	 * @param kafkaTemplate the template.
	 * @since 2.2.5
	 */
	protected void publish(ProducerRecord<Object, Object> outRecord, KafkaOperations<Object, Object> kafkaTemplate) {
		try {
			kafkaTemplate.send(outRecord).addCallback(result -> {
				LOGGER.debug(() -> "Successful dead-letter publication: " + result);
			}, ex -> {
				LOGGER.error(ex, () -> "Dead-letter publication failed for: " + outRecord);
			});
		}
		catch (Exception e) {
			LOGGER.error(e, () -> "Dead-letter publication failed for: " + outRecord);
		}
	}

	private void enhanceHeaders(Headers kafkaHeaders, ConsumerRecord<?, ?> record, Exception exception) {
		kafkaHeaders.add(
				new RecordHeader(KafkaHeaders.DLT_ORIGINAL_TOPIC, record.topic().getBytes(StandardCharsets.UTF_8)));
		kafkaHeaders.add(new RecordHeader(KafkaHeaders.DLT_ORIGINAL_PARTITION,
				ByteBuffer.allocate(Integer.BYTES).putInt(record.partition()).array()));
		kafkaHeaders.add(new RecordHeader(KafkaHeaders.DLT_ORIGINAL_OFFSET,
				ByteBuffer.allocate(Long.BYTES).putLong(record.offset()).array()));
		kafkaHeaders.add(new RecordHeader(KafkaHeaders.DLT_ORIGINAL_TIMESTAMP,
				ByteBuffer.allocate(Long.BYTES).putLong(record.timestamp()).array()));
		kafkaHeaders.add(new RecordHeader(KafkaHeaders.DLT_ORIGINAL_TIMESTAMP_TYPE,
				record.timestampType().toString().getBytes(StandardCharsets.UTF_8)));
		kafkaHeaders.add(new RecordHeader(KafkaHeaders.DLT_EXCEPTION_FQCN,
				exception.getClass().getName().getBytes(StandardCharsets.UTF_8)));
		String message = exception.getMessage();
		if (message != null) {
			kafkaHeaders.add(new RecordHeader(KafkaHeaders.DLT_EXCEPTION_MESSAGE,
					exception.getMessage().getBytes(StandardCharsets.UTF_8)));
		}
		kafkaHeaders.add(new RecordHeader(KafkaHeaders.DLT_EXCEPTION_STACKTRACE,
				getStackTraceAsString(exception).getBytes(StandardCharsets.UTF_8)));
	}

	private String getStackTraceAsString(Throwable cause) {
		StringWriter stringWriter = new StringWriter();
		PrintWriter printWriter = new PrintWriter(stringWriter, true);
		cause.printStackTrace(printWriter);
		return stringWriter.getBuffer().toString();
	}

}
