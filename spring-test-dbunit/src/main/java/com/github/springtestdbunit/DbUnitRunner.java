/*
 * Copyright 2002-2015 the original author or authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.github.springtestdbunit;

import com.github.springtestdbunit.annotation.*;
import com.github.springtestdbunit.assertion.DatabaseAssertion;
import com.github.springtestdbunit.dataset.DataSetLoader;
import com.github.springtestdbunit.dataset.DataSetModifier;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.dbunit.database.IDatabaseConnection;
import org.dbunit.dataset.CompositeDataSet;
import org.dbunit.dataset.IDataSet;
import org.dbunit.dataset.ITable;
import org.dbunit.dataset.filter.IColumnFilter;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Method;
import java.util.*;

/**
 * Internal delegate class used to run tests with support for {@link DatabaseSetup &#064;DatabaseSetup},
 * {@link DatabaseTearDown &#064;DatabaseTearDown} and {@link ExpectedDatabase &#064;ExpectedDatabase} annotations.
 *
 * @author Phillip Webb
 * @author Mario Zagar
 * @author Sunitha Rajarathnam
 * @author Oleksii Lomako
 */
class DbUnitRunner {

	private static final Log logger = LogFactory.getLog(DbUnitTestExecutionListener.class);

	/**
	 * Called before a test method is executed to perform any database setup.
	 * @param testContext The test context
	 * @throws Exception
	 */
	public void beforeTestMethod(DbUnitTestContext testContext) throws Exception {
//		Collection<DatabaseSetup> annotations = getAnnotations(testContext, DatabaseSetups.class, DatabaseSetup.class);
//		setupOrTeardown(testContext, true, AnnotationAttributes.get(annotations));
		Annotations<DatabaseSetup> annotations = Annotations.get(testContext, DatabaseSetups.class,
				DatabaseSetup.class);
		setupOrTeardown(testContext, true, AnnotationAttributes.get(annotations));
	}

	/**
	 * Called after a test method is executed to perform any database teardown and to check expected results.
	 * @param testContext The test context
	 * @throws Exception
	 */
	public void afterTestMethod(DbUnitTestContext testContext) throws Exception {
		try {
			try {
//				verifyExpected(testContext,
//						getAnnotations(testContext, ExpectedDatabases.class, ExpectedDatabase.class));
				verifyExpected(testContext,
						Annotations.get(testContext, ExpectedDatabases.class, ExpectedDatabase.class));
			} finally {
//				Collection<DatabaseTearDown> annotations = getAnnotations(testContext, DatabaseTearDowns.class,
//						DatabaseTearDown.class);

				Annotations<DatabaseTearDown> annotations = Annotations.get(testContext, DatabaseTearDowns.class,
						DatabaseTearDown.class);
				try {
					setupOrTeardown(testContext, false, AnnotationAttributes.get(annotations));
//					setupOrTeardown(testContext, false, AnnotationAttributes.get(annotations));
				} catch (RuntimeException ex) {
					if (testContext.getTestException() == null) {
						throw ex;
					}
					if (logger.isWarnEnabled()) {
						logger.warn("Unable to throw database cleanup exception due to existing test error", ex);
					}
				}
			}
		} finally {
			testContext.getConnections().closeAll();
		}
	}

	private <T extends Annotation> List<T> getAnnotations(DbUnitTestContext testContext,
			Class<? extends Annotation> containerType, Class<T> type) {
		List<T> annotations = new ArrayList<T>();
		addAnnotationToList(annotations, AnnotationUtils.findAnnotation(testContext.getTestClass(), type));
		addRepeatableAnnotationsToList(annotations,
				AnnotationUtils.findAnnotation(testContext.getTestClass(), containerType));
		addAnnotationToList(annotations, AnnotationUtils.findAnnotation(testContext.getTestMethod(), type));
		addRepeatableAnnotationsToList(annotations,
				AnnotationUtils.findAnnotation(testContext.getTestMethod(), containerType));
		return annotations;
	}

	private <T extends Annotation> void addAnnotationToList(List<T> annotations, T annotation) {
		if (annotation != null) {
			annotations.add(annotation);
		}
	}

	@SuppressWarnings("unchecked")
	private <T extends Annotation> void addRepeatableAnnotationsToList(List<T> annotations,
			Annotation annotationContainer) {
		if (annotationContainer != null) {
			T[] value = (T[]) AnnotationUtils.getValue(annotationContainer);
			for (T annotation : value) {
				annotations.add(annotation);
			}
		}
	}

	private void verifyExpected(DbUnitTestContext testContext, Annotations<ExpectedDatabase> annotations)
			throws Exception {
		if (testContext.getTestException() != null) {
			if (logger.isDebugEnabled()) {
				logger.debug("Skipping @DatabaseTest expectation due to test exception "
						+ testContext.getTestException().getClass());
			}
			return;
		}
		DatabaseConnections connections = testContext.getConnections();
		DataSetModifier modifier = getModifier(testContext, annotations);
		boolean override = false;
		for (ExpectedDatabase annotation : annotations.getMethodAnnotations()) {
			verifyExpected(testContext, connections, modifier, annotation);
			override |= annotation.override();
		}
		if (!override) {
			for (ExpectedDatabase annotation : annotations.getClassAnnotations()) {
				verifyExpected(testContext, connections, modifier, annotation);
			}
		}
	}

	private DataSetModifier getModifier(DbUnitTestContext testContext, Annotations<ExpectedDatabase> annotations) {
		DataSetModifiers modifiers = new DataSetModifiers();
		for (ExpectedDatabase annotation : annotations) {
			for (Class<? extends DataSetModifier> modifierClass : annotation.modifiers()) {
				modifiers.add(testContext.getTestInstance(), modifierClass);
			}
		}
		return modifiers;
	}

	private void verifyExpected(DbUnitTestContext testContext, List<ExpectedDatabase> annotations) throws Exception {
		if (testContext.getTestException() != null) {
			if (logger.isDebugEnabled()) {
				logger.debug("Skipping @DatabaseTest expectation due to test exception "
						+ testContext.getTestException().getClass());
			}
			return;
		}
		DatabaseConnections connections = testContext.getConnections();
		DataSetModifier modifier = getModifier(testContext, annotations);
		for (int i = annotations.size() - 1; i >= 0; i--) {
			ExpectedDatabase annotation = annotations.get(i);
			verifyExpected(testContext, connections, modifier, annotation);
			if (annotation.override()) {
				// No need to test any more
				return;
			}
		}

	}

	private void verifyExpected(DbUnitTestContext testContext, DatabaseConnections connections, DataSetModifier modifier, ExpectedDatabase annotation) throws Exception {
		String query = annotation.query();
		String table = annotation.table();

		List<IDataSet> expectedDataSets = loadDatasets(testContext, annotation.value(), modifier);
		IDatabaseConnection connection = connections.get(annotation.connection());
		if (expectedDataSets != null && expectedDataSets.size() > 0) {
            if (logger.isDebugEnabled()) {
                logger.debug("Veriftying @DatabaseTest expectation using " + annotation.value());
            }
			IDataSet expectedDataSet = new CompositeDataSet(expectedDataSets.toArray(new IDataSet[expectedDataSets.size()]));
            DatabaseAssertion assertion = annotation.assertionMode().getDatabaseAssertion();
            List<IColumnFilter> columnFilters = getColumnFilters(annotation);
            if (StringUtils.hasLength(query)) {
                Assert.hasLength(table, "The table name must be specified when using a SQL query");
                ITable expectedTable = expectedDataSet.getTable(table);
                ITable actualTable = connection.createQueryTable(table, query);
                assertion.assertEquals(expectedTable, actualTable,columnFilters);
            } else if (StringUtils.hasLength(table)) {
                ITable actualTable = connection.createTable(table);
                ITable expectedTable = expectedDataSet.getTable(table);
                assertion.assertEquals(expectedTable, actualTable,columnFilters);
            } else {
                IDataSet actualDataSet = connection.createDataSet();
                assertion.assertEquals(expectedDataSet, actualDataSet,columnFilters);
            }
        }
	}

	private List<IColumnFilter> getColumnFilters(ExpectedDatabase annotation) throws Exception {
		Class<? extends IColumnFilter>[] columnFilterClasses = annotation.columnFilters();
		List<IColumnFilter> columnFilters = new LinkedList<IColumnFilter>();
		for (Class<? extends IColumnFilter> columnFilterClass : columnFilterClasses) {
			columnFilters.add(columnFilterClass.newInstance());
		}
		return columnFilters;
	}

	private DataSetModifier getModifier(DbUnitTestContext testContext, List<ExpectedDatabase> annotations) {
		DataSetModifiers modifiers = new DataSetModifiers();
		for (ExpectedDatabase annotation : annotations) {
			for (Class<? extends DataSetModifier> modifierClass : annotation.modifiers()) {
				modifiers.add(testContext.getTestInstance(), modifierClass);
			}
		}
		return modifiers;
	}

	private void setupOrTeardown(DbUnitTestContext testContext, boolean isSetup,
			Collection<AnnotationAttributes> annotations) throws Exception {
		DatabaseConnections connections = testContext.getConnections();
		for (AnnotationAttributes annotation : annotations) {
			List<IDataSet> datasets = loadDataSets(testContext, annotation);
			DatabaseOperation operation = annotation.getType();
			org.dbunit.operation.DatabaseOperation dbUnitOperation = getDbUnitDatabaseOperation(testContext, operation);
			if (!datasets.isEmpty()) {
				if (logger.isDebugEnabled()) {
					logger.debug("Executing " + (isSetup ? "Setup" : "Teardown") + " of @DatabaseTest using "
							+ operation + " on " + datasets.toString());
				}
				IDatabaseConnection connection = connections.get(annotation.getConnection());
				IDataSet dataSet = new CompositeDataSet(datasets.toArray(new IDataSet[datasets.size()]));
				dbUnitOperation.execute(connection, dataSet);
			}
		}
	}

	private List<IDataSet> loadDataSets(DbUnitTestContext testContext, AnnotationAttributes annotation)
			throws Exception {
		List<IDataSet> datasets = new ArrayList<IDataSet>();
		for (String dataSetLocation : annotation.getValue()) {
			datasets.add(loadDataset(testContext, dataSetLocation, DataSetModifier.NONE));
		}
		return datasets;
	}

	private IDataSet loadDataset(DbUnitTestContext testContext, String dataSetLocation, DataSetModifier modifier)
			throws Exception {
		DataSetLoader dataSetLoader = testContext.getDataSetLoader();
		if (StringUtils.hasLength(dataSetLocation)) {
			IDataSet dataSet = dataSetLoader.loadDataSet(testContext.getTestClass(), dataSetLocation);
			dataSet = modifier.modify(dataSet);
			Assert.notNull(dataSet,
					"Unable to load dataset from \"" + dataSetLocation + "\" using " + dataSetLoader.getClass());
			return dataSet;
		}
		return null;
	}

	private List<IDataSet> loadDatasets(DbUnitTestContext testContext, String[] dataSetLocations, DataSetModifier modifier) throws Exception {
		List<IDataSet> dataSets = new ArrayList<IDataSet>();
		for (String dataSetLocation : dataSetLocations) {
			IDataSet dataSet = loadDataset(testContext, dataSetLocation, modifier);
			dataSets.add(dataSet);
		}
		return dataSets;
	}

	private org.dbunit.operation.DatabaseOperation getDbUnitDatabaseOperation(DbUnitTestContext testContext,
			DatabaseOperation operation) {
		org.dbunit.operation.DatabaseOperation databaseOperation = testContext.getDatbaseOperationLookup().get(
				operation);
		Assert.state(databaseOperation != null, "The database operation " + operation + " is not supported");
		return databaseOperation;
	}

	private static class AnnotationAttributes {

		private final DatabaseOperation type;

		private final String[] value;

		private final String connection;

		public AnnotationAttributes(Annotation annotation) {
			Assert.state((annotation instanceof DatabaseSetup) || (annotation instanceof DatabaseTearDown),
					"Only DatabaseSetup and DatabaseTearDown annotations are supported");
			Map<String, Object> attributes = AnnotationUtils.getAnnotationAttributes(annotation);
			this.type = (DatabaseOperation) attributes.get("type");
			this.value = (String[]) attributes.get("value");
			this.connection = (String) attributes.get("connection");
		}

		public DatabaseOperation getType() {
			return this.type;
		}

		public String[] getValue() {
			return this.value;
		}

		public String getConnection() {
			return this.connection;
		}

		public static <T extends Annotation> Collection<AnnotationAttributes> get(Annotations<T> annotations) {
			List<AnnotationAttributes> annotationAttributes = new ArrayList<AnnotationAttributes>();
			for (T annotation : annotations) {
				annotationAttributes.add(new AnnotationAttributes(annotation));
			}
			return annotationAttributes;
		}

	}

//	private static class AnnotationAttributes {
//
//		private final DatabaseOperation type;
//
//		private final String[] value;
//
//		private final String connection;
//
//		public AnnotationAttributes(Annotation annotation) {
//			Assert.state((annotation instanceof DatabaseSetup) || (annotation instanceof DatabaseTearDown),
//					"Only DatabaseSetup and DatabaseTearDown annotations are supported");
//			Map<String, Object> attributes = AnnotationUtils.getAnnotationAttributes(annotation);
//			this.type = (DatabaseOperation) attributes.get("type");
//			this.value = (String[]) attributes.get("value");
//			this.connection = (String) attributes.get("connection");
//		}
//
//		public DatabaseOperation getType() {
//			return this.type;
//		}
//
//		public String[] getValue() {
//			return this.value;
//		}
//
//		public String getConnection() {
//			return this.connection;
//		}
//
//		public static <T extends Annotation> Collection<AnnotationAttributes> get(Collection<T> annotations) {
//			List<AnnotationAttributes> annotationAttributes = new ArrayList<AnnotationAttributes>();
//			for (T annotation : annotations) {
//				annotationAttributes.add(new AnnotationAttributes(annotation));
//			}
//			return annotationAttributes;
//		}
//
//	}

    private static class Annotations<T extends Annotation> implements Iterable<T> {

        private final List<T> classAnnotations;

        private final List<T> methodAnnotations;

        private final List<T> allAnnotations;

        public Annotations(DbUnitTestContext context, Class<? extends Annotation> container, Class<T> annotation) {
            this.classAnnotations = getAnnotations(context.getTestClass(), container, annotation);
            this.methodAnnotations = getAnnotations(context.getTestMethod(), container, annotation);
            List<T> allAnnotations = new ArrayList<T>(this.classAnnotations.size() + this.methodAnnotations.size());
            allAnnotations.addAll(this.classAnnotations);
            allAnnotations.addAll(this.methodAnnotations);
            this.allAnnotations = Collections.unmodifiableList(allAnnotations);
        }

        private List<T> getAnnotations(AnnotatedElement element, Class<? extends Annotation> container,
                                       Class<T> annotation) {
            List<T> annotations = new ArrayList<T>();
            if (element instanceof Class) {
                addAnnotationToList(annotations, AnnotationUtils.findAnnotation((Class<?>)element, annotation));
            }
            if (element instanceof Method) {
                addRepeatableAnnotationsToList(annotations, AnnotationUtils.findAnnotation((Method)element, container));
            }
            return Collections.unmodifiableList(annotations);
        }

        private void addAnnotationToList(List<T> annotations, T annotation) {
            if (annotation != null) {
                annotations.add(annotation);
            }
        }

        @SuppressWarnings("unchecked")
        private void addRepeatableAnnotationsToList(List<T> annotations, Annotation container) {
            if (container != null) {
                T[] value = (T[]) AnnotationUtils.getValue(container);
                for (T annotation : value) {
                    annotations.add(annotation);
                }
            }
        }

        public List<T> getClassAnnotations() {
            return this.classAnnotations;
        }

        public List<T> getMethodAnnotations() {
            return this.methodAnnotations;
        }

        public Iterator<T> iterator() {
            return this.allAnnotations.iterator();
        }

        private static <T extends Annotation> Annotations<T> get(DbUnitTestContext testContext,
                                                                 Class<? extends Annotation> container, Class<T> annotation) {
            return new Annotations<T>(testContext, container, annotation);
        }

    }

}
