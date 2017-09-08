package org.conceptoriented.bistro.core.expr;

import org.conceptoriented.bistro.core.*;

import java.math.BigDecimal;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * TODO: add support for http://mathparser.org/
 */
class UdeJava implements UDE {

	public static String OUT_VARIABLE_NAME = "out";

    public boolean isExp4j = true;
    public boolean isEvalex = false;

    private static QNameBuilder parser = new QNameBuilder();

	// Formula
	protected String formula;

	protected Table table; // For resolution (binding). Formula terms will be resolved relative to this table

	protected boolean isEquality; // The formula is a single parameter without operations

	// Native expressions produced during translation and used during evaluation
	protected net.objecthunter.exp4j.Expression exp4jExpression;
	protected com.udojava.evalex.Expression evalexExpression;

	// Will be filled by parser and then augmented by binder
	protected List<ExprDependency> exprDependencies = new ArrayList<ExprDependency>();
	protected ExprDependency outDependency;

	//
	// EvaluatorExpr interface
	//
	@Override
	public void setParamPaths(List<NamePath> paths) {
		; // Not needed. Parameter paths are extracted from the formula itself
	}
	@Override
	public List<NamePath> getParamPaths() {
		List<NamePath> paths = new ArrayList<NamePath>();
		for(ExprDependency dep : this.exprDependencies) {
			paths.add(dep.qname);
		}
		return paths;
	}
	@Override public void setResolvedParamPaths(List<ColumnPath> paths) { ; } // Param paths are extracted from formula
	@Override
	public List<ColumnPath> getResolvedParamPaths() {
		List<ColumnPath> paths = new ArrayList<ColumnPath>();
		for(ExprDependency dep : this.exprDependencies) {
			paths.add(dep.columns);
		}
		return paths;
	}
	@Override
	public void translate(String formula) {
		this.translateError = null;
		this.formula = formula;
		if(this.formula == null || this.formula.isEmpty()) return;

		try {
			this.parse();
		}
		catch(Exception err) {
			if(this.translateError == null) { // Status has not been set by the failed method
				this.translateError = new BistroError(BistroErrorCode.TRANSLATE_ERROR, "Parse error", "Cannot parse the formula.");
			}
			return;
		}
		if(this.translateError != null) return;

		try {
			this.bind();
		}
		catch(Exception err) {
			if(this.translateError == null) { // Status has not been set by the failed method
				this.translateError = new BistroError(BistroErrorCode.TRANSLATE_ERROR, "Bind error", "Cannot resolve symbols.");
			}
			return;
		}
		if(this.translateError != null) return;

		try {
			this.build();
		}
		catch(Exception err) {
			if(this.translateError == null) { // Status has not been set by the failed method
				this.translateError = new BistroError(BistroErrorCode.TRANSLATE_ERROR, "Build error", "Cannot build evaluator object.");
			}
			return;
		}
		if(this.translateError != null) return;
	}
	private BistroError translateError;
	@Override
	public List<BistroError> getTranslateErrors() { // Find first error or null for no errors. Is meaningful only after translation.
		List<BistroError> ret = new ArrayList<BistroError>();
		if(this.translateError == null || this.translateError.code == BistroErrorCode.NONE) {
			return ret;
		}
		ret.add(this.translateError);
		return ret;
	}
	@Override
	public Object evaluate(Object[] params, Object out) {
		this.evaluateError = null;

		// Set all parameters in native expressions
		int paramNo = 0;
		for(ExprDependency dep : this.exprDependencies) {
			Object value = params[paramNo];
			if(value == null) value = Double.NaN;
			try {
				if(this.isEquality) {
					; // Do nothing
				}
				else if(this.isExp4j) {
					this.exp4jExpression.setVariable(dep.paramName, ((Number)value).doubleValue());
				}
				else if(this.isEvalex) {
					;
				}
			}
			catch(Exception e) {
				this.evaluateError = new BistroError(BistroErrorCode.EVALUATE_ERROR, "Evaluate error", "Error setting parameter values. " + e.getMessage());
				return null;
			}
			paramNo++;
		}

		// Set out value (if used)
		if(this.outDependency != null) {
			if(out == null) out = Double.NaN;
			try {
				if(this.isEquality) {
					; // Do nothing
				}
				else if(this.isExp4j) {
					this.exp4jExpression.setVariable(this.outDependency.paramName, ((Number)out).doubleValue());
				}
				else if(this.isEvalex) {
					;
				}
			}
			catch(Exception e) {
				this.evaluateError = new BistroError(BistroErrorCode.EVALUATE_ERROR, "Evaluate error", "Error setting parameter values. " + e.getMessage());
				return null;
			}
		}

		// Evaluate native expression
		Object ret = null;
		try {
			if(this.isEquality) {
				ret = params[0]; // Only one param exists for equalities
			}
			else if(this.isExp4j) {
				ret = this.exp4jExpression.evaluate();
			}
			else if(this.isEvalex) {
				ret = this.evalexExpression.eval();
			}
		}
		catch(Exception e) {
			this.evaluateError = new BistroError(BistroErrorCode.EVALUATE_ERROR, "Evaluate error", "Error evaluating expression. " + e.getMessage());
			return null;
		}

		return ret;
	}
	private BistroError evaluateError;
	@Override
	public BistroError getEvaluateError() { // Find first error or null for no errors. Is meaningful only after evaluation
		if(this.evaluateError == null || this.evaluateError.code == BistroErrorCode.NONE) {
			return null;
		}
		return this.evaluateError;
	}

	//
	// Parse
	//

	public static NamePath parsePath(String path) {
		NamePath ret = parser.buildQName(path); // TODO: There might be errors here, e.g., wrong characters in names
		return ret;
	}

	/**
	 * Parse formulas by possibly building a tree of expressions with primitive expressions in the leaves.
	 * The result of parsing is a list of symbols.
	 */
	protected void parse() {
		if(this.formula == null || this.formula.isEmpty()) return;

		this.exprDependencies.clear();
		this.outDependency = null;

		//
		// Find all occurrences of columns names (in square brackets or otherwise syntactically identified)
		//
		String ex =  "\\[(.*?)\\]";
		//String ex = "[\\[\\]]";
		Pattern p = Pattern.compile(ex,Pattern.DOTALL);
		Matcher matcher = p.matcher(this.formula);

		List<ExprDependency> names = new ArrayList<ExprDependency>();
		while(matcher.find())
		{
			int s = matcher.start();
			int e = matcher.end();
			String name = matcher.group();
			ExprDependency entry = new ExprDependency();
			entry.start = s;
			entry.end = e;
			names.add(entry);
		}

		//
		// Create paths by concatenating dot separated column name sequences
		//
		for(int i = 0; i < names.size(); i++) {
			if(i == names.size()-1) { // Last element does not have continuation
				this.exprDependencies.add(names.get(i));
				break;
			}

			int thisEnd = names.get(i).end;
			int nextStart = names.get(i+1).start;

			if(this.formula.substring(thisEnd, nextStart).trim().equals(".")) { // There is continuation.
				names.get(i+1).start = names.get(i).start; // Attach this name to the next name as a prefix
			}
			else { // No continuation. Ready to copy as path.
				this.exprDependencies.add(names.get(i));
			}
		}

    	//
		// Process the paths
		//
		for(ExprDependency dep : this.exprDependencies) {
			dep.pathName = this.formula.substring(dep.start, dep.end);
			dep.qname = this.parsePath(dep.pathName); // TODO: There might be errors here, e.g., wrong characters in names
		}

    	//
		// Detect identity expressions which have a single parameter without operations
		// It is a workaround to solve the problem of non-numeric expressions (used in links) which cannot be evaluated by a native expression library.
		// For equalities, the evaluator will process them separately without using native evaluator.
		//
		if(this.exprDependencies.size() == 1) {
			ExprDependency dep = this.exprDependencies.get(0);
			if(dep.pathName.equals(this.formula.trim())) {
				this.isEquality = true;
			}
			else {
				this.isEquality = false;
			}
		}

		// Detect out parameter and move out of this list to a separate variable
		int outParamNo = 0;
		for(ExprDependency dep : this.exprDependencies) {
			if(this.isOutputParameter(dep.qname)) {
				break;
			}
			outParamNo++;
		}
		if(outParamNo < this.exprDependencies.size()) {
			this.outDependency = this.exprDependencies.get(outParamNo);
			this.exprDependencies.remove(outParamNo);
		}
	}
	private boolean isOutputParameter(NamePath qname) {
		if(qname.names.size() != 1) return false;
		return this.isOutputParameter(qname.names.get(0));
	}
	private boolean isOutputParameter(String paramName) {
		if(paramName.equalsIgnoreCase("["+UdeJava.OUT_VARIABLE_NAME+"]")) {
			return true;
		}
		else if(paramName.equalsIgnoreCase(UdeJava.OUT_VARIABLE_NAME)) {
			return true;
		}
		return false;
	}

	//
	// Bind (resolve all parameters)
	//
	protected void bind() {
		for(ExprDependency dep : this.exprDependencies) {
			dep.columns = dep.qname.resolveColumns(this.table);
			if(dep.columns == null || dep.columns.getLength() < dep.qname.names.size()) {
				this.translateError = new BistroError(BistroErrorCode.TRANSLATE_ERROR, "Bind error", "Cannot resolve column path " + dep.pathName);
				return;
			}
		}
	}

	//
	// Build (native expression that can be evaluated)
	//

	public void build() {
		// Clean
		this.exp4jExpression = null;
		this.evalexExpression = null;

		// Build the final (native) expression
		if(this.isExp4j) {
			this.exp4jExpression = this.buildExp4jExpression();
		}
		else if(this.isEvalex) {
			this.evalexExpression = this.buildEvalexExpression();
		}
	}

	// Build exp4j expression
	protected net.objecthunter.exp4j.Expression buildExp4jExpression() {

		String transformedFormula = this.transformFormula();

		//
		// Create a list of variables used in the expression
		//
		Set<String> vars = new HashSet<String>();
		Map<String, Double> vals = new HashMap<String, Double>();
		for(ExprDependency dep : this.exprDependencies) {
			if(dep.paramName == null || dep.paramName.trim().isEmpty()) continue;
			vars.add(dep.paramName);
			vals.put(dep.paramName, 0.0);
		}
		// Set<String> vars = this.primExprDependencies.stream().map(x -> x.paramName).collect(Collectors.toCollection(HashSet::new));

		// Out variable (in principle, we can always add it to the expression)
		if(this.outDependency != null) {
			vars.add(this.outDependency.paramName);
			vals.put(this.outDependency.paramName, 0.0);
		}

		//
		// Create expression object with the transformed formula
		//
		net.objecthunter.exp4j.Expression exp = null;
		try {
			net.objecthunter.exp4j.ExpressionBuilder builder = new net.objecthunter.exp4j.ExpressionBuilder(transformedFormula);
			builder.variables(vars);
			exp = builder.build(); // Here we get parsing exceptions which might need be caught and processed
		}
		catch(Exception e) {
			this.translateError = new BistroError(BistroErrorCode.TRANSLATE_ERROR, "Expression error.", e.getMessage());
			return null;
		}

		//
		// Validate
		//
		exp.setVariables(vals); // Validation requires variables to be set
		net.objecthunter.exp4j.ValidationResult res = exp.validate(); // Boolean argument can be used to ignore unknown variables
		if(!res.isValid()) {
			this.translateError = new BistroError(BistroErrorCode.TRANSLATE_ERROR, "Expression error.", res.getErrors() != null && res.getErrors().size() > 0 ? res.getErrors().get(0) : "");
			return null;
		}

		return exp;
	}

	// Build Evalex expression
	protected com.udojava.evalex.Expression buildEvalexExpression() {

		String transformedFormula = this.transformFormula();

		//
		// Create a list of variables used in the expression
		//
		Set<String> vars = new HashSet<String>();
		Map<String, Double> vals = new HashMap<String, Double>();
		for(ExprDependency dep : this.exprDependencies) {
			if(dep.paramName == null || dep.paramName.trim().isEmpty()) continue;
			vars.add(dep.paramName);
			vals.put(dep.paramName, 0.0);
		}
		// Set<String> vars = this.primExprDependencies.stream().map(x -> x.paramName).collect(Collectors.toCollection(HashSet::new));

		// Out variable (in principle, we can always add it to the expression)
		if(this.outDependency != null) {
			vars.add(this.outDependency.paramName);
			vals.put(this.outDependency.paramName, 0.0);
		}

		//
		// Create expression object with the transformed formula
		//
		final com.udojava.evalex.Expression exp;
		try {
			exp = new com.udojava.evalex.Expression(transformedFormula);
		}
		catch(Exception e) {
			this.translateError = new BistroError(BistroErrorCode.TRANSLATE_ERROR, "Expression error.", e.getMessage());
			return null;
		}

		//
		// Validate
		//
		vars.forEach(x -> exp.setVariable(x, new BigDecimal(1.0)));
    	try {
    		exp.toRPN(); // Generates prefixed representation but can be used to check errors (variables have to be set in order to correctly determine parse errors)
    	}
    	catch(com.udojava.evalex.Expression.ExpressionException ee) {
			this.translateError = new BistroError(BistroErrorCode.TRANSLATE_ERROR, "Expression error.", ee.getMessage());
			return null;
    	}

		return exp;
	}

	// Replace all occurrences of column paths in the formula by variable names from the symbol table
	private String transformFormula() {
		StringBuffer buf = new StringBuffer(this.formula);

		// Input parameters
		for(int i = this.exprDependencies.size()-1; i >= 0; i--) {
			ExprDependency dep = this.exprDependencies.get(i);
			if(dep.start < 0 || dep.end < 0) continue; // Some dependencies are not from formula (e.g., group path)
			dep.paramName = "__p__" + i;
			buf.replace(dep.start, dep.end, dep.paramName);
		}

		// Current out parameter
		ExprDependency dep = this.outDependency;
		if(dep != null) {
			dep.paramName = "__p__" + this.exprDependencies.size();
			buf.replace(dep.start, dep.end, dep.paramName);
		}

		return buf.toString();
	}

	public UdeJava() {
	}
	public UdeJava(String formula, Table table) {
		this.formula = formula;
		this.table = table;

		this.translate(formula);
	}
}

class ExprDependency {
	public int start;
	public int end;
	public String pathName; // Original param paths
	public String paramName;
	public NamePath qname; // Parsed param paths
	ColumnPath columns; // Resolved param paths
}
