/*
 * Copyright 2019 the original author or authors.
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
package org.springframework.data.mongodb.repository.query;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import lombok.Value;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.bson.Document;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;
import org.springframework.data.domain.Sort;
import org.springframework.data.domain.Sort.Direction;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.aggregation.AggregationResults;
import org.springframework.data.mongodb.core.aggregation.TypedAggregation;
import org.springframework.data.mongodb.core.convert.DbRefResolver;
import org.springframework.data.mongodb.core.convert.MappingMongoConverter;
import org.springframework.data.mongodb.core.convert.MongoConverter;
import org.springframework.data.mongodb.core.mapping.MongoMappingContext;
import org.springframework.data.mongodb.repository.Aggregation;
import org.springframework.data.mongodb.repository.Person;
import org.springframework.data.projection.ProjectionFactory;
import org.springframework.data.projection.SpelAwareProxyProjectionFactory;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.core.support.DefaultRepositoryMetadata;
import org.springframework.data.repository.query.QueryMethodEvaluationContextProvider;
import org.springframework.expression.spel.standard.SpelExpressionParser;

/**
 * @author Christoph Strobl
 */
@RunWith(MockitoJUnitRunner.class)
public class StringBasedAggregationUnitTests {

	SpelExpressionParser PARSER = new SpelExpressionParser();

	@Mock MongoOperations operations;
	@Mock DbRefResolver dbRefResolver;
	@Mock AggregationResults aggregationResults;
	MongoConverter converter;

	private static final String RAW_SORT_STRING = "{ '$sort' : { 'lastname' : -1 } }";
	private static final String RAW_GROUP_BY_LASTNAME_STRING = "{ '$group': { '_id' : '$lastname', 'names' : { '$addToSet' : '$firstname' } } }";
	private static final String GROUP_BY_LASTNAME_STRING_WITH_PARAMETER_PLACEHOLDER = "{ '$group': { '_id' : '$lastname', names : { '$addToSet' : '$?0' } } }";
	private static final String GROUP_BY_LASTNAME_STRING_WITH_SPEL_PARAMETER_PLACEHOLDER = "{ '$group': { '_id' : '$lastname', 'names' : { '$addToSet' : '$?#{[0]}' } } }";

	private static final Document SORT = Document.parse(RAW_SORT_STRING);
	private static final Document GROUP_BY_LASTNAME = Document.parse(RAW_GROUP_BY_LASTNAME_STRING);

	@Before
	public void setUp() {

		converter = new MappingMongoConverter(dbRefResolver, new MongoMappingContext());
		when(operations.getConverter()).thenReturn(converter);
		when(operations.aggregate(any(TypedAggregation.class), any())).thenReturn(aggregationResults);
	}

	@Test // DATAMONGO-2153
	public void plainStringAggregation() {

		AggregationInvocation invocation = executeAggregation("plainStringAggregation");

		assertThat(inputTypeOf(invocation)).isEqualTo(Person.class);
		assertThat(targetTypeOf(invocation)).isEqualTo(PersonAggregate.class);
		assertThat(pipelineOf(invocation)).containsExactly(GROUP_BY_LASTNAME, SORT);
	}

	@Test // DATAMONGO-2153
	public void returnSingleObject() {

		PersonAggregate expected = new PersonAggregate();
		when(aggregationResults.getUniqueMappedResult()).thenReturn(Collections.singletonList(expected));

		assertThat(executeAggregation("returnSingleEntity").result).isEqualTo(expected);
	}

	@Test // DATAMONGO-2153
	public void returnSingleObjectThrowsError() {

		when(aggregationResults.getUniqueMappedResult()).thenThrow(new IllegalArgumentException("o_O"));

		assertThatExceptionOfType(IllegalArgumentException.class)
				.isThrownBy(() -> executeAggregation("returnSingleEntity"));
	}

	@Test // DATAMONGO-2153
	public void returnCollection() {

		List<PersonAggregate> expected = Collections.singletonList(new PersonAggregate());
		when(aggregationResults.getMappedResults()).thenReturn(expected);

		assertThat(executeAggregation("returnCollection").result).isEqualTo(expected);
	}

	@Test // DATAMONGO-2153
	public void returnRawResultType() {
		assertThat(executeAggregation("returnRawResultType").result).isEqualTo(aggregationResults);
	}

	@Test // DATAMONGO-2153
	public void plainStringAggregationWithSortParameter() {

		AggregationInvocation invocation = executeAggregation("plainStringAggregation",
				Sort.by(Direction.DESC, "lastname"));

		assertThat(inputTypeOf(invocation)).isEqualTo(Person.class);
		assertThat(targetTypeOf(invocation)).isEqualTo(PersonAggregate.class);
		assertThat(pipelineOf(invocation)).containsExactly(GROUP_BY_LASTNAME, SORT);
	}

	@Test // DATAMONGO-2153
	public void replaceParameter() {

		AggregationInvocation invocation = executeAggregation("parameterReplacementAggregation", "firstname");

		assertThat(inputTypeOf(invocation)).isEqualTo(Person.class);
		assertThat(targetTypeOf(invocation)).isEqualTo(PersonAggregate.class);
		assertThat(pipelineOf(invocation)).containsExactly(GROUP_BY_LASTNAME);
	}

	@Test // DATAMONGO-2153
	public void replaceSpElParameter() {

		AggregationInvocation invocation = executeAggregation("spelParameterReplacementAggregation", "firstname");

		assertThat(inputTypeOf(invocation)).isEqualTo(Person.class);
		assertThat(targetTypeOf(invocation)).isEqualTo(PersonAggregate.class);
		assertThat(pipelineOf(invocation)).containsExactly(GROUP_BY_LASTNAME);
	}

	// TODO: Collation tests

	private AggregationInvocation executeAggregation(String name, Object... args) {

		Class<?>[] argTypes = Arrays.stream(args).map(Object::getClass).toArray(size -> new Class<?>[size]);
		StringBasedAggregation aggregation = createAggregationForMethod(name, argTypes);

		ArgumentCaptor<TypedAggregation> aggregationCaptor = ArgumentCaptor.forClass(TypedAggregation.class);
		ArgumentCaptor<Class> targetTypeCaptor = ArgumentCaptor.forClass(Class.class);

		Object result = aggregation.execute(args);

		verify(operations).aggregate(aggregationCaptor.capture(), targetTypeCaptor.capture());

		return new AggregationInvocation(aggregationCaptor.getValue(), targetTypeCaptor.getValue(), result);
	}

	private StringBasedAggregation createAggregationForMethod(String name, Class<?>... parameters) {

		try {

			Method method = SampleRepository.class.getMethod(name, parameters);
			ProjectionFactory factory = new SpelAwareProxyProjectionFactory();
			MongoQueryMethod queryMethod = new MongoQueryMethod(method, new DefaultRepositoryMetadata(SampleRepository.class),
					factory, converter.getMappingContext());
			return new StringBasedAggregation(queryMethod, operations, PARSER, QueryMethodEvaluationContextProvider.DEFAULT);

		} catch (Exception e) {
			throw new IllegalArgumentException(e.getMessage(), e);
		}
	}

	private List<Document> pipelineOf(AggregationInvocation invocation) {
		return invocation.aggregation
				.toPipeline(org.springframework.data.mongodb.core.aggregation.Aggregation.DEFAULT_CONTEXT);
	}

	private Class<?> inputTypeOf(AggregationInvocation invocation) {
		return invocation.aggregation.getInputType();
	}

	private Class<?> targetTypeOf(AggregationInvocation invocation) {
		return invocation.getTargetType();
	}

	private interface SampleRepository extends Repository<Person, Long> {

		@Aggregation({ RAW_GROUP_BY_LASTNAME_STRING, RAW_SORT_STRING })
		PersonAggregate plainStringAggregation();

		@Aggregation(RAW_GROUP_BY_LASTNAME_STRING)
		PersonAggregate plainStringAggregation(Sort sort);

		@Aggregation(RAW_GROUP_BY_LASTNAME_STRING)
		PersonAggregate returnSingleEntity();

		@Aggregation(RAW_GROUP_BY_LASTNAME_STRING)
		List<PersonAggregate> returnCollection();

		@Aggregation(RAW_GROUP_BY_LASTNAME_STRING)
		AggregationResults<PersonAggregate> returnRawResultType();

		@Aggregation(RAW_GROUP_BY_LASTNAME_STRING)
		AggregationResults<PersonAggregate> returnRawResults();

		@Aggregation(GROUP_BY_LASTNAME_STRING_WITH_PARAMETER_PLACEHOLDER)
		PersonAggregate parameterReplacementAggregation(String attribute);

		@Aggregation(GROUP_BY_LASTNAME_STRING_WITH_SPEL_PARAMETER_PLACEHOLDER)
		PersonAggregate spelParameterReplacementAggregation(String arg0);
	}

	static class PersonAggregate {

	}

	@Value
	static class AggregationInvocation {

		final TypedAggregation<?> aggregation;
		final Class<?> targetType;
		final Object result;
	}
}
