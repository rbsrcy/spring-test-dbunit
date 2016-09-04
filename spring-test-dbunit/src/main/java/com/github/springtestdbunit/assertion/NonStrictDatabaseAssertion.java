/*
 * Copyright 2002-2013 the original author or authors
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

package com.github.springtestdbunit.assertion;

import org.dbunit.Assertion;
import org.dbunit.DatabaseUnitException;
import org.dbunit.dataset.*;
import org.dbunit.dataset.filter.IColumnFilter;

import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;

/**
 * Implements non-strict database assertion strategy : compares data sets ignoring all tables and columns which are not
 * specified in expected data set but possibly exist in actual data set.
 *
 * @author Mario Zagar
 * @author Sunitha Rajarathnam
 */
class NonStrictDatabaseAssertion implements DatabaseAssertion {

	public void assertEquals(IDataSet expectedDataSet, IDataSet actualDataSet) throws DatabaseUnitException {
		for (String tableName : expectedDataSet.getTableNames()) {
			ITable expectedTable = expectedDataSet.getTable(tableName);
			ITable actualTable = actualDataSet.getTable(tableName);
			assertEquals(expectedTable, actualTable);
		}
	}

	public void assertEquals(ITable expectedTable, ITable actualTable) throws DatabaseUnitException {
		String[] ignoredColumns = getColumnsToIgnore(expectedTable.getTableMetaData(), actualTable.getTableMetaData());
		Assertion.assertEqualsIgnoreCols(expectedTable, actualTable, ignoredColumns);
	}

	@Override
	public void assertEquals(IDataSet expectedDataSet, IDataSet actualDataSet, List<IColumnFilter> columnFilters) throws DatabaseUnitException {
		for (String tableName : expectedDataSet.getTableNames()) {
			ITable expectedTable = expectedDataSet.getTable(tableName);
			ITable actualTable = actualDataSet.getTable(tableName);
			assertEquals(expectedTable, actualTable, columnFilters);
		}
	}

	@Override
	public void assertEquals(ITable expectedTable, ITable actualTable, List<IColumnFilter> columnFilters) throws DatabaseUnitException {
		Set<String> ignoredColumns = getColumnsToIgnore(expectedTable.getTableMetaData(),
				actualTable.getTableMetaData(), columnFilters);
		Assertion.assertEqualsIgnoreCols(expectedTable, actualTable,
				ignoredColumns.toArray(new String[ignoredColumns.size()]));
	}

	protected String[] getColumnsToIgnore(ITableMetaData expectedMetaData, ITableMetaData actualMetaData)
			throws DataSetException {
		Column[] notSpecifiedInExpected = Columns.getColumnDiff(expectedMetaData, actualMetaData).getActual();
		List<String> result = new LinkedList<String>();
		for (Column column : notSpecifiedInExpected) {
			result.add(column.getColumnName());
		}
		return result.toArray(new String[result.size()]);
	}

	private Set<String> getColumnsToIgnore(ITableMetaData expectedMetaData, ITableMetaData actualMetaData,
										   List<IColumnFilter> columnFilters) throws DataSetException {
		if (columnFilters.size() == 0) {
			return getColumnsToIgnore2(expectedMetaData, actualMetaData);
		}
		Set<String> ignoredColumns = new LinkedHashSet<String>();
		for (IColumnFilter filter : columnFilters) {
			FilteredTableMetaData filteredExpectedMetaData = new FilteredTableMetaData(expectedMetaData, filter);
			ignoredColumns.addAll(getColumnsToIgnore2(filteredExpectedMetaData, actualMetaData));
		}
		return ignoredColumns;
	}

	protected Set<String> getColumnsToIgnore2(ITableMetaData expectedMetaData, ITableMetaData actualMetaData)
			throws DataSetException {
		Column[] notSpecifiedInExpected = Columns.getColumnDiff(expectedMetaData, actualMetaData).getActual();
		Set<String> result = new LinkedHashSet<String>();
		for (Column column : notSpecifiedInExpected) {
			result.add(column.getColumnName());
		}
		return result;
	}

}
