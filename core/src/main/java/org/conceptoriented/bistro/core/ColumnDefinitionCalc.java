package org.conceptoriented.bistro.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * It is an implementation of definition for calc columns.
 * It loops through the main table, reads inputs, passes them to the expression and then write the output to the main column.
 */
public class ColumnDefinitionCalc extends ColumnDefinitionBase {

	Expression expr;

	@Override
	public void eval() {
		// Evaluate calc expression
		if(this.expr == null) { // Default
			super.column.setValue(); // Reset
		}
		else {
			super.evaluateExpr(expr, null);
		}
	}

	@Override
	public List<Column> getDependencies() {
		List<ColumnPath> paths = this.expr.getParameterPaths();
		List<Column> deps = ColumnPath.getColumns(paths);
		return deps;
	}

	public ColumnDefinitionCalc(Column column, Evaluator lambda, ColumnPath[] paths) {
		super(column);
		this.expr = new Expr(lambda, paths);
	}

	public ColumnDefinitionCalc(Column column, Evaluator lambda, Column[] columns) {
		super(column);
		ColumnPath[] paths = new ColumnPath[columns.length];
		for (int i = 0; i < columns.length; i++) {
            paths[i] = new ColumnPath(columns[i]);
		}

		this.expr = new Expr(lambda, paths);
	}

	public ColumnDefinitionCalc(Column column, Expression expr) {
		super(column);
		this.expr = expr;
	}
}