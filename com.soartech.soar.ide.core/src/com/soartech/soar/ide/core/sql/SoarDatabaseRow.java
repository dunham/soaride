package com.soartech.soar.ide.core.sql;

import java.io.StringReader;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;

import org.eclipse.jface.text.IDocument;
import org.eclipse.ui.IEditorInput;

import com.soartech.soar.ide.core.model.ast.Action;
import com.soartech.soar.ide.core.model.ast.AttributeTest;
import com.soartech.soar.ide.core.model.ast.AttributeValueMake;
import com.soartech.soar.ide.core.model.ast.AttributeValueTest;
import com.soartech.soar.ide.core.model.ast.BinaryPreference;
import com.soartech.soar.ide.core.model.ast.Condition;
import com.soartech.soar.ide.core.model.ast.ConditionForOneIdentifier;
import com.soartech.soar.ide.core.model.ast.ConjunctiveTest;
import com.soartech.soar.ide.core.model.ast.Constant;
import com.soartech.soar.ide.core.model.ast.DisjunctionTest;
import com.soartech.soar.ide.core.model.ast.ForcedUnaryPreference;
import com.soartech.soar.ide.core.model.ast.FunctionCall;
import com.soartech.soar.ide.core.model.ast.NaturallyUnaryPreference;
import com.soartech.soar.ide.core.model.ast.Pair;
import com.soartech.soar.ide.core.model.ast.ParseException;
import com.soartech.soar.ide.core.model.ast.PositiveCondition;
import com.soartech.soar.ide.core.model.ast.PreferenceSpecifier;
import com.soartech.soar.ide.core.model.ast.RHSValue;
import com.soartech.soar.ide.core.model.ast.RelationalTest;
import com.soartech.soar.ide.core.model.ast.SimpleTest;
import com.soartech.soar.ide.core.model.ast.SingleTest;
import com.soartech.soar.ide.core.model.ast.SoarParser;
import com.soartech.soar.ide.core.model.ast.SoarProductionAst;
import com.soartech.soar.ide.core.model.ast.Test;
import com.soartech.soar.ide.core.model.ast.ValueMake;
import com.soartech.soar.ide.core.model.ast.ValueTest;
import com.soartech.soar.ide.core.model.ast.VarAttrValMake;
import com.soartech.soar.ide.core.sql.SoarDatabaseEvent.Type;

/**
 * Represents a row in a table. Valid tables are in the enum
 * SoarDatabaseRow.Table. Rows in join tables should be represented by a
 * SoarDatabaseJoinRow.
 * 
 * @author miller
 * 
 */
public class SoarDatabaseRow implements ISoarDatabaseRow {

	public enum Table {
		AGENTS, PROBLEM_SPACES, OPERATORS,

		// Rules
		RULES,
		CONDITIONS,
		POSITIVE_CONDITIONS,
		CONDITION_FOR_ONE_IDENTIFIERS,
		ATTRIBUTE_VALUE_TESTS,
		ATTRIBUTE_TESTS,
		VALUE_TESTS,
		TESTS,
		CONJUNCTIVE_TESTS,
		SIMPLE_TESTS,
		DISJUNCTION_TESTS,
		RELATIONAL_TESTS,
		RELATIONS,
		SINGLE_TESTS,
		CONSTANTS,
		ACTIONS,
		VAR_ATTR_VAL_MAKES,
		ATTRIBUTE_VALUE_MAKES,
		FUNCTION_CALLS,
		FUNCTION_NAMES,
		RHS_VALUES,
		VALUE_MAKES,
		PREFERENCES,
		PREFERENCE_SPECIFIERS,
		NATURALLY_UNARY_PREFERENCES,
		BINARY_PREFERENCES,
		FORCED_UNARY_PREFERENCES,

		// Datamap
		DATAMAP_ATTRIBUTES,
		DATAMAP_ENUMERATION_VALUES,
		DATAMAP_INTEGER_VALUES,
		DATAMAP_FLOAT_VALUES,
		DATAMAP_STRING_VALUES,
		;

		public String tableName() {
			return toString().toLowerCase();
		}

		public String shortName() {
			String name = tableName();
			return name.substring(0, name.length() - 1);
		}

		public String idName() {
			return shortName() + "_id";
		}
	}

	public static Table getTableNamed(String name) {
		for (Table t : Table.values()) {
			if (name.equalsIgnoreCase(t.name())) {
				return t;
			}
		}
		return null;
	}
	
	// Maps a table onto its possible child tables.
	private static HashMap<Table, ArrayList<Table>> childTables = new HashMap<Table, ArrayList<Table>>();

	// Maps a table onto its possible parent tables.
	private static HashMap<Table, ArrayList<Table>> parentTables = new HashMap<Table, ArrayList<Table>>();

	// Maps a class from an abstract syntax tree onto the equivelent Table.
	private static HashMap<Class<?>, Table> tableForAstNode = new HashMap<Class<?>, Table>();

	// Maps a table onto the a list of child Tables which should be displayed in
	// folders.
	private static HashMap<Table, ArrayList<Table>> childFolders = new HashMap<Table, ArrayList<Table>>();

	// Maps a table onto a list of all tables it's joined to.
	// This isn't multi-directional; the key must be the first table name in the
	// join table.
	// (see getTablesJoinedToTable)
	private static HashMap<Table, ArrayList<Table>> joinedTables = new HashMap<Table, ArrayList<Table>>();

	// Maps a table onto a list of all tables it's directionally joined to.
	// The key is the source table; each value is a child table.
	private static HashMap<Table, ArrayList<Table>> directedJoinedTables = new HashMap<Table, ArrayList<Table>>(); 
	
	// Maps a table onto a list of tables that each should have a row generated
	// automatically
	// when the key table is created.
	private static HashMap<Table, ArrayList<Table>> automaticChildren = new HashMap<Table, ArrayList<Table>>();
	
	// Maps a Table onto a list of editable columns for that table.
	private static HashMap<Table, ArrayList<EditableColumn>> editableColumns = new HashMap<Table, ArrayList<EditableColumn>>();
	
	private Table table;
	private int id;
	private SoarDatabaseConnection db;
	private String name = null;
	private HashMap<Table, SoarDatabaseRowFolder> folders = new HashMap<Table, SoarDatabaseRowFolder>();

	private static boolean initted = false;

	public SoarDatabaseRow(Table table, int id, SoarDatabaseConnection db) {

		if (!initted) {
			init();
		}

		this.table = table;
		this.id = id;
		this.db = db;

		// Set up folders
		if (childFolders.containsKey(table)) {
			ArrayList<Table> folderTypes = childFolders.get(table);
			for (Table type : folderTypes) {
				SoarDatabaseRowFolder folder = new SoarDatabaseRowFolder(this,
						type);
				folders.put(type, folder);
			}
		}
	}

	@Override
	public String toString() {
		return getName();
	}

	public String getName() {
		if (name != null) {
			return name;
		}
		String sql = "select (name) from " + table.tableName() + " where id=?";
		StatementWrapper ps = db.prepareStatement(sql);
		ps.setInt(1, id);
		ResultSet rs = ps.executeQuery();
		try {
			if (rs.next()) {
				name = rs.getString("name");
			} else {
				name = table.tableName() + ": NO ROW WITH ID " + id;
			}
			ps.close();
		} catch (SQLException e) {
			e.printStackTrace();
		}
		return name;
	}

	public void setName(String name) {
		String sql = "update " + table.tableName() + " set name=? where id=?";
		StatementWrapper ps = db.prepareStatement(sql);
		ps.setString(1, name);
		ps.setInt(2, id);
		ps.execute();
	}
	
	/**
	 * CAUTION:
	 * Doesn't use a prepared statment.
	 * Doesn't properly escape values.
	 * @param column
	 * @param value
	 */
	public void updateValue(String column, String value) {
		updateValues(new String[] {column}, new String[] {value});
	}
	
	/**
	 * CAUTION:
	 * Doesn't use a prepared statment.
	 * Doesn't properly escape values.
	 * @param columns
	 * @param values
	 */
	public void updateValues(String[] columns, String[] values) {
		int size = Math.min(columns.length, values.length);
		String sql = "update " + table.tableName() + " set ";
		for (int i = 0; i < size; ++i) {
			sql += columns[i] + "=" + values[i];
			if (i != size - 1) {
				sql += ", ";
			} else {
				sql += " where id=" + this.id;
			}
		}
		db.execute(sql);
	}

	public Table getTable() {
		return table;
	}

	public int getID() {
		return id;
	}

	private void delete() {
		String sql = "delete from " + table.tableName() + " where id=?";
		StatementWrapper ps = db.prepareStatement(sql);
		ps.setInt(1, id);
		ps.execute();

		// Also remove joins.
		removeAllJoins();
	}

	public void removeAllJoins() {
		// Remove undirected joins
		ArrayList<Table> tables = getTablesJoinedToTable(this.table);
		for (Table t : tables) {
			ArrayList<ISoarDatabaseRow> joinedRows = getJoinedRowsFromTable(t);
			for (ISoarDatabaseRow other : joinedRows) {
				if (other instanceof SoarDatabaseRow) {
					unjoinRows(this, (SoarDatabaseRow) other, db);
				}
			}
		}
		
		// Remove directed joins where this is the child
		// (directed joins with this as the parent have already been removed in deleteAllChildren() ).
		ArrayList<ISoarDatabaseRow> parentRows = getParentJoinedRows();
		for (ISoarDatabaseRow iParent : parentRows) {
			if (iParent instanceof SoarDatabaseRow) {
				SoarDatabaseRow parent = (SoarDatabaseRow) iParent;
				directedUnjoinRows(parent, this, db);
			}
		}
	}

	public ArrayList<Table> getChildTables() {
		if (childTables.containsKey(table)) {
			ArrayList<Table> children = childTables.get(table);
			return children;
		}
		return new ArrayList<Table>();
	}

	/**
	 * Returns rows, folders, and join folders.
	 * 
	 * @param includeFolders
	 *            Whether to include child folders.
	 * @param includeChildrenInFolders
	 *            Whether to include rows that are contained in child folders.
	 * @param includeJoinedItems
	 *            Whether to include joined tables.
	 * @param includeDatamapNodes
	 *            Whether to include datamap nodes.
	 * @return The child elements of this row.
	 */
	public ArrayList<ISoarDatabaseRow> getChildren(boolean includeFolders,
			boolean includeChildrenInFolders,
			boolean includeJoinedItems,
			boolean includeDirectionalJoinedItems,
			boolean includeDatamapNodes) {
		ArrayList<ISoarDatabaseRow> ret = new ArrayList<ISoarDatabaseRow>();
		ArrayList<Table> children = getChildTables();
		for (Table t : children) {
			boolean isFolder = folders.containsKey(t);
			if (isFolder && includeFolders) {
				ret.add(folders.get(t));
			}
			if ((!isFolder) || includeChildrenInFolders) {
				if (t != Table.DATAMAP_ATTRIBUTES || includeDatamapNodes) {
					String sql = "select * from " + t.tableName() + " where "
							+ table.idName() + "=?";
					StatementWrapper ps = db.prepareStatement(sql);
					ps.setInt(1, id);
					ResultSet rs = ps.executeQuery();
					try {
						while (rs.next()) {
							SoarDatabaseRow row = new SoarDatabaseRow(t, rs
									.getInt("id"), db);
							ret.add(row);
						}
						rs.getStatement().close();
					} catch (SQLException e) {
						e.printStackTrace();
					}
				}
			}
		}

		if (includeJoinedItems) {
			ArrayList<Table> joinedTables = getTablesJoinedToTable(table);
			for (Table t : joinedTables) {
				SoarDatabaseJoinFolder folder = new SoarDatabaseJoinFolder(this, t);
				ret.add(folder);
			}
		}
		
		if (includeDirectionalJoinedItems) {
			ret.addAll(getDirectedJoinedChildren());
		}

		return ret;
	}
	
	public ArrayList<ISoarDatabaseRow> getDirectedJoinedChildren() {		
		ArrayList<ISoarDatabaseRow> ret = new ArrayList<ISoarDatabaseRow>();
		if (directedJoinedTables.containsKey(table)) {
			ArrayList<Table> joinedTables = directedJoinedTables.get(table);
			for (Table t : joinedTables) {
				SoarDatabaseJoinFolder folder = new SoarDatabaseJoinFolder(this, t);
				ret.add(folder);
			}
		}
		return ret;
	}

	/**
	 * Doesn't return folders.
	 * 
	 * @param type
	 * @return
	 */
	public ArrayList<ISoarDatabaseRow> getChildrenOfType(Table type) {
		ArrayList<ISoarDatabaseRow> ret = new ArrayList<ISoarDatabaseRow>();
		ArrayList<Table> children = getChildTables();
		for (Table t : children) {
			if (t == type) {
				String sql = "select * from " + t.tableName() + " where "
						+ table.idName() + "=?";
				StatementWrapper ps = db.prepareStatement(sql);
				ps.setInt(1, id);
				ResultSet rs = ps.executeQuery();
				try {
					while (rs.next()) {
						SoarDatabaseRow row = new SoarDatabaseRow(t, rs
								.getInt("id"), db);
						ret.add(row);
					}
					ps.close();
				} catch (SQLException e) {
					e.printStackTrace();
				}
			}
		}
		return ret;
	}

	public boolean hasChildren() {
		ArrayList<Table> children = getChildTables();
		for (Table t : children) {
			String sql = "select * from " + t.tableName() + " where "
					+ table.idName() + "=?";
			StatementWrapper ps = db.prepareStatement(sql);
			ps.setInt(1, id);
			ResultSet rs = ps.executeQuery();
			try {
				if (rs.next()) {
					ps.close();
					return true;
				}
				ps.close();
			} catch (SQLException e) {
				e.printStackTrace();
			}
		}
		if (folders.size() > 0) {
			return true;
		}
		if (getTablesJoinedToTable(table).size() > 0) {
			return true;
		}
		if (directedJoinedTables.containsKey(table)) {
			return directedJoinedTables.get(table).size() > 0; 
		}
		return false;
	}

	public boolean hasChildrenOfType(Table type) {
		String sql = "select * from " + type.tableName() + " where "
				+ table.idName() + "=?";
		StatementWrapper ps = db.prepareStatement(sql);
		ps.setInt(1, id);
		ResultSet rs = ps.executeQuery();
		try {
			if (rs.next()) {
				ps.close();
				return true;
			}
			ps.close();
		} catch (SQLException e) {
			e.printStackTrace();
		}
		return false;
	}

	public ArrayList<Table> getParentTables() {
		if (parentTables.containsKey(table)) {
			ArrayList<Table> parents = parentTables.get(table);
			return parents;
		}
		return new ArrayList<Table>();
	}

	/**
	 * Should only return a List of size 0 or 1.
	 * 
	 * @return
	 */
	public ArrayList<SoarDatabaseRow> getParentRows() {
		ArrayList<SoarDatabaseRow> ret = new ArrayList<SoarDatabaseRow>();
		ArrayList<Table> parents = getParentTables();
		for (Table t : parents) {
			String sql = "select * from " + t.tableName()
					+ " where id = (select " + t.idName() + " from "
					+ table.tableName() + " where id=?)";
			StatementWrapper ps = db.prepareStatement(sql);
			ps.setInt(1, id);
			ResultSet rs = ps.executeQuery();
			try {
				while (rs.next()) {
					SoarDatabaseRow row = new SoarDatabaseRow(t, rs.getInt("id"), db);
					ret.add(row);
				}
				ps.close();
			} catch (SQLException e) {
				e.printStackTrace();
			}
		}
		return ret;
	}
	
	public boolean hasParentRows() {
		ArrayList<Table> parents = getParentTables();
		for (Table t : parents) {
			String sql = "select * from " + t.tableName()
					+ " where id = (select " + t.idName() + " from "
					+ table.tableName() + " where id=?)";
			StatementWrapper ps = db.prepareStatement(sql);
			ps.setInt(1, id);
			ResultSet rs = ps.executeQuery();
			try {
				if (rs.next()) {
					ps.close();
					return true;
				}
				ps.close();
			} catch (SQLException e) {
				e.printStackTrace();
			}
		}
		return false;
	}

	/**
	 * Returns a list of all rows which are parents in a directional join with this row. 
	 * @return
	 */
	public ArrayList<ISoarDatabaseRow> getParentJoinedRows() {
		ArrayList<ISoarDatabaseRow> ret = new ArrayList<ISoarDatabaseRow>();
		ArrayList<Table> parentTables = getParentTablesDirectedJoinedToTable(table);
		for (Table parentTable : parentTables) {
			String joinTableName = directedJoinTableName(parentTable, table);
			String sql = "select * from " + joinTableName + " where child_id=?";
			StatementWrapper sw = db.prepareStatement(sql);
			sw.setInt(1, id);
			ResultSet rs = sw.executeQuery();
			try {
				while (rs.next()) {
					int id = rs.getInt("parent_id");
					SoarDatabaseRow parentRow = new SoarDatabaseRow(parentTable, id, db);
					ret.add(parentRow);
				}
			} catch (SQLException e) {
				e.printStackTrace();
			}
			sw.close();
		}
		return ret;
	}
	
	/**
	 * 
	 * @return True if this.getParentJoinedRows().size() > 0.
	 */
	public boolean hasParentJoinedRows() {
		ArrayList<Table> parentTables = getParentTablesDirectedJoinedToTable(table);
		for (Table parentTable : parentTables) {
			String joinTableName = directedJoinTableName(parentTable, table);
			String sql = "select * from " + joinTableName + " where second_id=?";
			StatementWrapper sw = db.prepareStatement(sql);
			sw.setInt(1, id);
			ResultSet rs = sw.executeQuery();
			try {
				if (rs.next()) {
					sw.close();
					return true;
				}
			} catch (SQLException e) {
				e.printStackTrace();
			}
			sw.close();
		}
		return false;
	}
	
	/**
	 * 
	 * @param type If non-null, returns the first row of this type
	 * encountered along upward traversal. If null, returns
	 * the top-level row.
	 * @return
	 */
	public SoarDatabaseRow getTopLevelRow(Table type) {
		if (table == type) {
			return this;
		}
		SoarDatabaseRow ret = this;
		ArrayList<SoarDatabaseRow> parents = ret.getParentRows();
		while (parents.size() > 0) {
			ret = parents.get(0);
			if (type != null && ret.getTable() == type) {
				return ret;
			}
			parents = ret.getParentRows();
		}
		if (type == null || ret.getTable() == type) {
			return ret;
		} else {
			return null;
		}
	}
	
	public SoarDatabaseRow getTopLevelRow() {
		return getTopLevelRow(null);
	}
	
	
	public ArrayList<ISoarDatabaseRow> getDescendantsOfType(Table type) {
		return getDescendantsOfType(type, new HashSet<ISoarDatabaseRow>());
	}
	
	private ArrayList<ISoarDatabaseRow> getDescendantsOfType(Table type, HashSet<ISoarDatabaseRow> visitedRows) {
		ArrayList<ISoarDatabaseRow> ret = new ArrayList<ISoarDatabaseRow>();
		
		ArrayList<ISoarDatabaseRow> children = getChildren(false, true, false, false, true);
		for (ISoarDatabaseRow child : children) {
			if (!visitedRows.contains(child)) {
				visitedRows.add(child);
				
				if (child instanceof SoarDatabaseRow) {
					SoarDatabaseRow childRow = (SoarDatabaseRow) child;
					ArrayList<ISoarDatabaseRow> temp = childRow.getDescendantsOfType(type, visitedRows);
					ret.addAll(temp);
					if (childRow.getTable() == type) {
						ret.add(childRow);
					}
				}
				
			}
		}
		
		return ret;
	}

	public ArrayList<ISoarDatabaseRow> getJoinedRowsFromTable(Table other) {
		ArrayList<ISoarDatabaseRow> ret = new ArrayList<ISoarDatabaseRow>();
		
		// First, add directionless joins.
		if (tablesAreJoined(this.table, other)) {
			String joinTableName = joinTableName(this.table, other);
			Table[] orderedTables = orderJoinedTables(this.table, other);
			String thisTableIdName = orderedTables[0] == this.table ? "first_id" : "second_id";
			String sql = "select * from " + joinTableName + " where " + thisTableIdName + "=?";
			StatementWrapper ps = db.prepareStatement(sql);
			ps.setInt(1, id);
			ResultSet rs = ps.executeQuery();
			try {
				while (rs.next()) {
					int otherId = rs.getInt(other.idName());
					SoarDatabaseRow row = new SoarDatabaseRow(other, otherId, db);
					ret.add(row);
				}
				ps.close();
			} catch (SQLException e) {
				e.printStackTrace();
			}
		}
		
		// Add directed joins.
		if (tablesAreDirectedJoined(this.table, other)) {
			String joinTableName = directedJoinTableName(this.table, other);
			String sql = "select * from " + joinTableName + " where parent_id=?";
			StatementWrapper ps = db.prepareStatement(sql);
			ps.setInt(1, this.id);
			ResultSet rs = ps.executeQuery();
			try {
				while (rs.next()) {
					int id = rs.getInt("child_id");
					SoarDatabaseRow temp = new SoarDatabaseRow(other, id, db);
					ret.add(temp);
				}
				ps.close();
			} catch (SQLException e) {
				e.printStackTrace();
			}
		}
		
		return ret;
	}

	public boolean hasJoinedRowsFromTable(Table other) {
		boolean ret = false;
		if (tablesAreJoined(this.table, other)) {
			String joinTableName = joinTableName(this.table, other);
			Table[] orderedTables = orderJoinedTables(this.table, other);
			String thisTableIdName = orderedTables[0] == this.table ? "first_id" : "second_id";
			String sql = "select * from " + joinTableName + " where " + thisTableIdName + "=?";
			StatementWrapper ps = db.prepareStatement(sql);
			ps.setInt(1, this.id);
			ResultSet rs = ps.executeQuery();
			try {
				if (rs.next()) {
					ret = true;
				}
				ps.close();
			} catch (SQLException e) {
				e.printStackTrace();
			}
		}
		if (!ret && tablesAreDirectedJoined(this.table, other)) {
			String joinTableName = directedJoinTableName(this.table, other);
			String sql = "select * from " + joinTableName + " where parent_id=?";
			StatementWrapper ps = db.prepareStatement(sql);
				ps.setInt(1, this.id);
				ResultSet rs = ps.executeQuery();
				try {
					if (rs.next()) {
						ret = true;
					}
					ps.close();
				} catch (SQLException e) {
					e.printStackTrace();
				}
		}
		return ret;
	}

	// Create a new undirected join entry between this row and the other row.
	public static void joinRows(SoarDatabaseRow first, SoarDatabaseRow second,
			SoarDatabaseConnection db) {

		// First, make sure that the rows are joinable.
		assert tablesAreJoined(first.table, second.table);

		// Make sure these rows aren't already joined.
		boolean alreadyJoined = rowsAreJoined(first, second, db);
		if (alreadyJoined) {
			return;
		}

		// Create new join row.
		String joinTableName = joinTableName(first.table, second.table);
		Table[] orderedTables = orderJoinedTables(first.table, second.table);
		String firstTableIdName = orderedTables[0] == first.table ? "first_id" : "second_id";
		String secondTableIdName = orderedTables[0] == second.table ? "first_id" : "second_id";
		String sql = "insert into " + joinTableName + " (" + firstTableIdName + ", " + secondTableIdName + ") values " + "(?,?)";
		StatementWrapper ps = db.prepareStatement(sql);
		ps.setInt(1, first.id);
		ps.setInt(2, second.id);
		ps.execute();
	}
	
	// Removes an undirected join between the two rows, if one exists.
	// Order of parameters doesn't matter.
	public static void unjoinRows(SoarDatabaseRow firstRow,
			SoarDatabaseRow secondRow, SoarDatabaseConnection db) {
		Table[] tables = SoarDatabaseRow.orderJoinedTables(firstRow.getTable(),
				secondRow.getTable());
		if (tables[0] != firstRow.getTable()) {
			// Swap first and second rows.
			SoarDatabaseRow temp = firstRow;
			firstRow = secondRow;
			secondRow = temp;
		}
		
		String sql = "delete from " + SoarDatabaseRow.joinTableName(tables[0], tables[1]) + " where first_id=? and second_id=?";
		StatementWrapper ps = db.prepareStatement(sql);
		ps.setInt(1, firstRow.id);
		ps.setInt(2, secondRow.id);
		ps.execute();
	}

	public static void directedJoinRows(SoarDatabaseRow parent, SoarDatabaseRow child, SoarDatabaseConnection db) {
		if (!tablesAreDirectedJoined(parent.getTable(), child.getTable())) {
			return;
		}
		String joinTable = directedJoinTableName(parent.getTable(), child.getTable());
		String sql = "insert into " + joinTable + " (parent_id, child_id) values (?,?)";
		StatementWrapper ps = db.prepareStatement(sql);
		ps.setInt(1, parent.id);
		ps.setInt(2, child.id);
		ps.execute();
	}
	
	public static void directedUnjoinRows(SoarDatabaseRow parent, SoarDatabaseRow child, SoarDatabaseConnection db) {
		if (!tablesAreDirectedJoined(parent.getTable(), child.getTable())) {
			return;
		}
		String joinTable = directedJoinTableName(parent.getTable(), child.getTable());
		String sql = "delete from " + joinTable + " where parent_id=? and child_id=?";
		StatementWrapper ps = db.prepareStatement(sql);
		ps.setInt(1, parent.id);
		ps.setInt(2, child.id);
		ps.execute();
	}
	
	/**
	 * Creates and returns a new row.
	 * 
	 * @param childTable
	 *            The type of child row to create.
	 * @param name
	 *            The name of the new row.
	 * @return The new row.
	 * @throws Exception
	 */
	public SoarDatabaseRow createChild(Table childTable, String name) {
		db.createChild(this, childTable, name);

		// Get the row
		String sql = "select * from " + childTable.tableName()
				+ " where id=(last_insert_rowid())";
		StatementWrapper ps = db.prepareStatement(sql);
		int id = -1;
		try {
			ResultSet rs = ps.executeQuery();
			if (rs.next()) {
				id = rs.getInt("id");
			}
			ps.close();
		} catch (SQLException e) {
			e.printStackTrace();
		}

		// Should have been assigned to recently created row's ID.
		assert id != -1;

		SoarDatabaseRow ret = new SoarDatabaseRow(childTable, id, db);

		// Check and see if there are children that should be automatically
		// generated also.
		ArrayList<Table> automaticChildren = SoarDatabaseRow.automaticChildren.get(childTable);
		if (automaticChildren != null) {
			for (Table t : automaticChildren) {
				ret.createChild(t, "New " + t.shortName());
			}
		}

		return ret;
	}
	
	/**
	 * Creates a new row.
	 * The new row isn't a normal child of this row. Instead,
	 * the new row is connected to this row by a directed join.
	 * @param childTable
	 * @param name
	 * @return
	 */
	public SoarDatabaseRow createJoinedChild(Table childTable, String name) {
		
		db.insert(childTable, new String[][] {{ "name" , "\"" + name + "\"" }});

		// Get the row
		String sql = "select * from " + childTable.tableName()
				+ " where id=(last_insert_rowid())";
		StatementWrapper ps = db.prepareStatement(sql);
		int id = -1;
		try {
			ResultSet rs = ps.executeQuery();
			if (rs.next()) {
				id = rs.getInt("id");
			}
			ps.close();
		} catch (SQLException e) {
			e.printStackTrace();
		}

		// Should have been assigned to recently created row's ID.
		assert id != -1;

		SoarDatabaseRow ret = new SoarDatabaseRow(childTable, id, db);
		
		// Directed-join the rows
		directedJoinRows(this, ret, db);

		// Check and see if there are children that should be automatically
		// generated also.
		ArrayList<Table> automaticChildren = SoarDatabaseRow.automaticChildren.get(childTable);
		if (automaticChildren != null) {
			for (Table t : automaticChildren) {
				ret.createChild(t, "New " + t.shortName());
			}
		}

		return ret;
	}

	public void createChildrenFromAstNode(Object node) throws Exception {
		ArrayList<Table> childTables = getChildTables();
		Table childTable = tableForAstNode.get(node.getClass());
		SqlArgsAndChildNodes argsAndNodes = sqlArgsAndChildNodes(node,
				childTable);
		SoarDatabaseRow childRow;

		if (childTable == this.table) {
			// This should only happen when 'node' is a SoarProductionAST and
			// this is already a 'Rule'
			childRow = this;
		} else if (childTables.contains(childTable)) {
			// create databse entry
			String[][] sqlArgs = argsAndNodes.sqlArgs;
			db.createChild(this, childTable, sqlArgs);

			// Get created row
			String sql = "select * from " + childTable.tableName()
					+ " where id=(last_insert_rowid())";
			StatementWrapper ps = db.prepareStatement(sql);
			int id = -1;
			try {
				ResultSet rs = ps.executeQuery();
				if (rs.next()) {
					id = rs.getInt("id");
				}
				ps.close();
			} catch (SQLException e) {
				e.printStackTrace();
			}
			
			// Should have been assigned to recently created row's ID.
			assert id != -1;

			childRow = new SoarDatabaseRow(childTable, id, db);
		} else {
			throw new Exception("No child table of current type \""
					+ table.shortName() + "\" for AST node \"" + childTable
					+ "\"");
		}

		// recursively create new entries
		Object[] childNodes = argsAndNodes.childNodes;
		for (Object childNode : childNodes) {
			childRow.createChildrenFromAstNode(childNode);
		}
	}

	/**
	 * Deletes all children.
	 * Also deletes directed-joined children if
	 * the child has no parents (normal parents or directed-join parents).
	 * @param alsoDeleteThis
	 */
	public void deleteAllChildren(boolean alsoDeleteThis) {
		ArrayList<ISoarDatabaseRow> childRows = getChildren(false, true, false, false, true);
		for (ISoarDatabaseRow child : childRows) {
			if (child instanceof SoarDatabaseRow) {
				((SoarDatabaseRow) child).deleteAllChildren(true);
			}
		}
		ArrayList<ISoarDatabaseRow> childJoinedRows = getDirectedJoinedChildren();
		for (ISoarDatabaseRow child : childJoinedRows) {
			if (child instanceof SoarDatabaseRow) {
				SoarDatabaseRow childRow = (SoarDatabaseRow) child;
				directedUnjoinRows(this, childRow, db);
				if (!childRow.hasParentJoinedRows() && !childRow.hasParentRows()) {
					childRow.deleteAllChildren(true);
				}
			}
		}
		if (alsoDeleteThis) {
			delete();
		}
	}
	
	public ArrayList<EditableColumn> getEditableColumns() {
		if (editableColumns.containsKey(table)) {
			ArrayList<EditableColumn> ret = editableColumns.get(table);
			if (ret != null) {
				return ret;
			}
		}
		return new ArrayList<EditableColumn>();
	}
	
	public Object getEditableColumnValue(EditableColumn column) {
		String sql = "Select " + column.getName() + " from " + table.tableName() + " where id=?";
		StatementWrapper sw = db.prepareStatement(sql);
		sw.setInt(1, id);
		ResultSet rs = sw.executeQuery();
		Object ret = null;
		try {
			if (rs.next()) {
				ret = rs.getObject(column.getName());
			}
		} catch (SQLException e) {
			e.printStackTrace();
		}
		sw.close();
		return ret;
	}
	
	public void editColumnValue(EditableColumn column, Object newValue) {
		if (column.objectIsRightType(newValue)) {
			String sql = "update " + table.tableName() + " set " + column.getName() + "=?";
			StatementWrapper sw = db.prepareStatement(sql);
			switch (EditableColumn.typeForObject(newValue)) {
			case FLOAT:
				sw.setFloat(1, (Float)newValue);
				break;
			case INTEGER:
				sw.setInt(1, (Integer)newValue);
				break;
			case STRING:
				sw.setString(1, (String)newValue);
				break;
			}
			sw.execute();
		}
	}

	/**
	 * Encapsulates information neccesary to add a row to the database.
	 * 
	 * @author miller
	 * 
	 */
	private class SqlArgsAndChildNodes {
		public String[][] sqlArgs;
		public Object[] childNodes;

		public SqlArgsAndChildNodes(ArrayList<String[]> sqlArgs,
				Object[] childNodes) {
			int argsLength = sqlArgs.size();
			this.sqlArgs = new String[argsLength][2];
			for (int i = 0; i < sqlArgs.size(); ++i) {
				String[] pair = sqlArgs.get(i);
				this.sqlArgs[i][0] = pair[0];
				this.sqlArgs[i][1] = pair[1];
			}
			this.childNodes = childNodes;
		}
	}

	/**
	 * Takes a Java Object from a Soar Production AST, and its corresponding
	 * Table, and returns a SqlArgsAndChildNodes that encapsulates the
	 * information required to add a row to the database.
	 * 
	 * @param node
	 * @param nodeTable
	 * @return
	 */
	private SqlArgsAndChildNodes sqlArgsAndChildNodes(Object node,
			Table nodeTable) {

		ArrayList<String[]> sqlArgs = new ArrayList<String[]>();
		Object[] childNodes = new Object[0]; // = null;

		Class<?> nodeClass = node.getClass();
		String name = nodeTable.shortName();

		String sqlTrue = "1";
		String sqlFalse = "0";

		if (nodeClass == SoarProductionAst.class) {
			Object[] conditions = ((SoarProductionAst) node).getConditions()
					.toArray();
			Object[] actions = ((SoarProductionAst) node).getActions()
					.toArray();
			childNodes = new Object[conditions.length + actions.length];
			System.arraycopy(conditions, 0, childNodes, 0, conditions.length);
			System.arraycopy(actions, 0, childNodes, conditions.length,
					actions.length);
		} else if (nodeClass == Condition.class) {
			Object positiveCondition = ((Condition) node)
					.getPositiveCondition();
			childNodes = new Object[] { positiveCondition };
			boolean isNegated = ((Condition) node).isNegated();
			sqlArgs.add(new String[] { "is_negated",
					isNegated ? sqlTrue : sqlFalse });
			if (isNegated)
				name += " (negated)";
		} else if (nodeClass == PositiveCondition.class) {
			boolean isConjunction = ((PositiveCondition) node).isConjunction();
			sqlArgs.add(new String[] { "is_conjunction",
					isConjunction ? sqlTrue : sqlFalse });
			if (isConjunction) {
				childNodes = ((PositiveCondition) node).getConjunction()
						.toArray();
			} else {
				childNodes = new Object[] { ((PositiveCondition) node)
						.getConditionForOneIdentifier() };
			}
			if (isConjunction)
				name += " (conjunction)";
		} else if (nodeClass == ConditionForOneIdentifier.class) {
			childNodes = ((ConditionForOneIdentifier) node)
					.getAttributeValueTests().toArray();
			boolean hasState = ((ConditionForOneIdentifier) node).hasState();
			sqlArgs.add(new String[] { "has_state",
					hasState ? sqlTrue : sqlFalse });
			Pair pair = ((ConditionForOneIdentifier) node).getVariable();
			String variable = pair.getString();
			sqlArgs.add(new String[] { "variable", "\"" + variable + "\"" });
			name += " (variable: " + variable + ")";
			if (hasState)
				name += " (has state)";
		} else if (nodeClass == AttributeValueTest.class) {
			Object[] attributeTests = ((AttributeValueTest) node)
					.getAttributeTests().toArray();
			Object[] valueTests = ((AttributeValueTest) node).getValueTests()
					.toArray();
			childNodes = new Object[attributeTests.length + valueTests.length];
			System.arraycopy(attributeTests, 0, childNodes, 0,
					attributeTests.length);
			System.arraycopy(valueTests, 0, childNodes, attributeTests.length,
					valueTests.length);
			sqlArgs.add(new String[] {
					"is_negated",
					((AttributeValueTest) node).isNegated() ? sqlTrue
							: sqlFalse });
		} else if (nodeClass == AttributeTest.class) {
			childNodes = new Object[] { ((AttributeTest) node).getTest() };
		} else if (nodeClass == Test.class) {
			boolean isConjunctiveTest = ((Test) node).isConjunctiveTest();
			sqlArgs.add(new String[] { "is_conjunctive_test",
					isConjunctiveTest ? sqlTrue : sqlFalse });
			if (isConjunctiveTest) {
				childNodes = new Object[] { ((Test) node).getConjunctiveTest() };
			} else {
				childNodes = new Object[] { ((Test) node).getSimpleTest() };
			}
			name += isConjunctiveTest ? " (conjunctive test)"
					: " (simple test)";
		} else if (nodeClass == SimpleTest.class) {
			boolean isDisjunctionTest = ((SimpleTest) node).isDisjunctionTest();
			sqlArgs.add(new String[] { "is_disjunction_test",
					isDisjunctionTest ? sqlTrue : sqlFalse });
			if (isDisjunctionTest) {
				childNodes = new Object[] { ((SimpleTest) node)
						.getDisjunctionTest() };
			} else {
				childNodes = new Object[] { ((SimpleTest) node)
						.getRelationalTest() };
			}
			name += isDisjunctionTest ? " (disjunction test)"
					: " (relational test)";
		} else if (nodeClass == DisjunctionTest.class) {
			childNodes = ((DisjunctionTest) node).getConstants().toArray();
		} else if (nodeClass == RelationalTest.class) {
			childNodes = new Object[] { ((RelationalTest) node).getSingleTest() };
			int relation = ((RelationalTest) node).getRelation();
			sqlArgs.add(new String[] { "relation", "" + relation });
			name += " (" + RelationalTest.RELATIONS[relation] + ")";
		} else if (nodeClass == SingleTest.class) {
			boolean isConstant = ((SingleTest) node).isConstant();
			sqlArgs.add(new String[] { "is_constant",
					isConstant ? sqlTrue : sqlFalse });
			if (isConstant) {
				childNodes = new Object[] { ((SingleTest) node).getConstant() };
				name += " (constant)";
			} else {
				Pair pair = ((SingleTest) node).getVariable();
				String variable = pair.getString();
				sqlArgs
						.add(new String[] { "variable", "\"" + variable + "\"" });
				name += " (variable: " + variable + ")";
			}
		} else if (nodeClass == ConjunctiveTest.class) {
			childNodes = ((ConjunctiveTest) node).getSimpleTests().toArray();
		} else if (nodeClass == ValueTest.class) {
			childNodes = new Object[] { ((ValueTest) node).getTest() };
			boolean hasAcceptablePreference = ((ValueTest) node)
					.hasAcceptablePreference();
			sqlArgs.add(new String[] { "has_acceptable_preference",
					hasAcceptablePreference ? sqlTrue : sqlFalse });
			if (hasAcceptablePreference)
				name += " (has acceptable preference)";
		} else if (nodeClass == Action.class) {
			boolean isVarAttrValMake = ((Action) node).isVarAttrValMake();
			sqlArgs.add(new String[] { "is_var_attr_val_make",
					isVarAttrValMake ? sqlTrue : sqlFalse });
			if (isVarAttrValMake) {
				childNodes = new Object[] { ((Action) node).getVarAttrValMake() };
			} else {
				childNodes = new Object[] { ((Action) node).getFunctionCall() };
			}
			name += isVarAttrValMake ? " (variable attribute make)"
					: " (function call)";
		} else if (nodeClass == VarAttrValMake.class) {
			childNodes = ((VarAttrValMake) node).getAttributeValueMakes()
					.toArray();
			Pair pair = ((VarAttrValMake) node).getVariable();
			String variable = pair.getString();
			sqlArgs.add(new String[] { "variable", "\"" + variable + "\"" });
			name += " (variable: " + variable + ")";
		} else if (nodeClass == AttributeValueMake.class) {
			Object[] rhsValues = ((AttributeValueMake) node).getRHSValues()
					.toArray();
			Object[] valueMakes = ((AttributeValueMake) node).getValueMakes()
					.toArray();
			childNodes = new Object[rhsValues.length + valueMakes.length];
			System.arraycopy(rhsValues, 0, childNodes, 0, rhsValues.length);
			System.arraycopy(valueMakes, 0, childNodes, rhsValues.length,
					valueMakes.length);
		} else if (nodeClass == RHSValue.class) {
			boolean isConstant = ((RHSValue) node).isConstant();
			boolean isFunctionCall = ((RHSValue) node).isConstant();
			boolean isVariable = ((RHSValue) node).isConstant();
			sqlArgs.add(new String[] { "is_constant",
					isConstant ? sqlTrue : sqlFalse });
			sqlArgs.add(new String[] { "is_function_call",
					isFunctionCall ? sqlTrue : sqlFalse });
			sqlArgs.add(new String[] { "is_variable",
					isVariable ? sqlTrue : sqlFalse });
			if (isConstant) {
				childNodes = new Object[] { ((RHSValue) node).getConstant() };
				name += " (constant)";
			} else if (isFunctionCall) {
				childNodes = new Object[] { ((RHSValue) node).getFunctionCall() };
				name += " (function call)";
			} else if (isVariable) {
				Pair pair = ((RHSValue) node).getVariable();
				String variable = pair.getString();
				sqlArgs
						.add(new String[] { "variable", "\"" + variable + "\"" });
				name += " (variable: " + variable + ")";
			}
		} else if (nodeClass == Constant.class) {
			int constantType = ((Constant) node).getConstantType();
			sqlArgs.add(new String[] { "constant_type", "" + constantType });
			if (constantType == Constant.FLOATING_CONST) {
				float floatingConst = ((Constant) node).getFloatConst();
				sqlArgs
						.add(new String[] { "floating_const",
								"" + floatingConst });
				name += " (floating constant: " + floatingConst + ")";
			} else if (constantType == Constant.INTEGER_CONST) {
				int intConst = ((Constant) node).getIntConst();
				sqlArgs.add(new String[] { "integer_const", "" + intConst });
				name += " (integer constant: " + intConst + ")";
			} else if (constantType == Constant.SYMBOLIC_CONST) {
				String symConst = ((Constant) node).getSymConst();
				sqlArgs.add(new String[] { "symbolic_const",
						"\"" + symConst + "\"" });
				name += " (symbolic constant: " + symConst + ")";
			}
		} else if (nodeClass == FunctionCall.class) {
			childNodes = ((FunctionCall) node).getRHSValues().toArray();
			Pair pair = ((FunctionCall) node).getFunctionName();
			String variable = pair.getString();
			sqlArgs.add(new String[] { "function_name", variable });
			name += " (variable: " + variable + ")";
		} else if (nodeClass == ValueMake.class) {
			Object[] preferenceSpecifiers = objectsArrayFromIterator(((ValueMake) node)
					.getPreferenceSpecifiers());
			Object[] rhsValue = new Object[] { ((ValueMake) node).getRHSValue() };
			childNodes = new Object[preferenceSpecifiers.length
					+ rhsValue.length];
			System.arraycopy(preferenceSpecifiers, 0, childNodes, 0,
					preferenceSpecifiers.length);
			System.arraycopy(rhsValue, 0, childNodes,
					preferenceSpecifiers.length, rhsValue.length);
		} else if (nodeClass == PreferenceSpecifier.class) {
			childNodes = new Object[] { ((PreferenceSpecifier) node).getRHS() };
			boolean isUnaryPreference = ((PreferenceSpecifier) node)
					.isUnaryPreference();
			int preferenceSpecifierType = ((PreferenceSpecifier) node)
					.getPreferenceSpecifierType();
			sqlArgs.add(new String[] { "is_unary_preference",
					isUnaryPreference ? sqlTrue : sqlFalse });
			sqlArgs.add(new String[] { "preference_specifier_type",
					"" + preferenceSpecifierType });
			if (isUnaryPreference)
				name += " (unary preference)";
			name += " (preference: "
					+ PreferenceSpecifier.PREFERENCES[preferenceSpecifierType]
					+ ")";
		}

		// Every row has a name -- this could change later
		sqlArgs.add(new String[] { "name", "\"" + name + "\"" });

		SqlArgsAndChildNodes ret = new SqlArgsAndChildNodes(sqlArgs, childNodes);
		return ret;
	}

	private <T> Object[] objectsArrayFromIterator(Iterator<T> it) {
		ArrayList<T> list = new ArrayList<T>();
		while (it.hasNext()) {
			list.add(it.next());
		}
		return list.toArray();
	}

	// Static initialization methods:

	private static void init() {

		// table problem spaces has foreign key agent_id:
		addParent(Table.PROBLEM_SPACES, Table.AGENTS);
		addParent(Table.OPERATORS, Table.AGENTS);
		addParent(Table.RULES, Table.AGENTS);

		// Which tables have folders
		ArrayList<Table> agentFolders = new ArrayList<Table>();
		agentFolders.add(Table.PROBLEM_SPACES);
		agentFolders.add(Table.OPERATORS);
		agentFolders.add(Table.RULES);
		childFolders.put(Table.AGENTS, agentFolders);
		
		// Which tables have editable columns
		addEditableColumnToTable(Table.DATAMAP_INTEGER_VALUES, new EditableColumn("max_value", EditableColumn.Type.INTEGER));
		addEditableColumnToTable(Table.DATAMAP_INTEGER_VALUES, new EditableColumn("min_value", EditableColumn.Type.INTEGER));
		addEditableColumnToTable(Table.DATAMAP_FLOAT_VALUES, new EditableColumn("max_value", EditableColumn.Type.FLOAT));
		addEditableColumnToTable(Table.DATAMAP_FLOAT_VALUES, new EditableColumn("min_value", EditableColumn.Type.FLOAT));

		// Declare joined tables.
		joinTables(Table.RULES, Table.PROBLEM_SPACES);
		joinTables(Table.RULES, Table.OPERATORS);
		joinTables(Table.OPERATORS, Table.PROBLEM_SPACES);
		joinTables(Table.DATAMAP_ATTRIBUTES, Table.DATAMAP_ATTRIBUTES);
		
		// Declare directional joined tables.
		directedJoinTables(Table.DATAMAP_ATTRIBUTES, Table.DATAMAP_ATTRIBUTES);
		directedJoinTables(Table.DATAMAP_ATTRIBUTES, Table.DATAMAP_ENUMERATION_VALUES);
		directedJoinTables(Table.DATAMAP_ATTRIBUTES, Table.DATAMAP_INTEGER_VALUES);
		directedJoinTables(Table.DATAMAP_ATTRIBUTES, Table.DATAMAP_FLOAT_VALUES);
		directedJoinTables(Table.DATAMAP_ATTRIBUTES, Table.DATAMAP_STRING_VALUES);

		// rule structure
		addParent(Table.CONDITIONS, Table.RULES);
		addParent(Table.POSITIVE_CONDITIONS, Table.CONDITIONS);
		addParent(Table.CONDITION_FOR_ONE_IDENTIFIERS, Table.POSITIVE_CONDITIONS);
		addParent(Table.ATTRIBUTE_VALUE_TESTS, Table.CONDITION_FOR_ONE_IDENTIFIERS);
		addParent(Table.ATTRIBUTE_TESTS, Table.ATTRIBUTE_VALUE_TESTS);
		addParent(Table.VALUE_TESTS, Table.ATTRIBUTE_VALUE_TESTS);
		addParent(Table.VALUE_TESTS, Table.ATTRIBUTE_TESTS);
		addParent(Table.TESTS, Table.VALUE_TESTS);
		addParent(Table.TESTS, Table.ATTRIBUTE_TESTS);
		addParent(Table.CONJUNCTIVE_TESTS, Table.TESTS);
		addParent(Table.SIMPLE_TESTS, Table.CONJUNCTIVE_TESTS);
		addParent(Table.SIMPLE_TESTS, Table.TESTS);
		addParent(Table.DISJUNCTION_TESTS, Table.SIMPLE_TESTS);
		addParent(Table.RELATIONAL_TESTS, Table.SIMPLE_TESTS);
		addParent(Table.RELATIONS, Table.RELATIONAL_TESTS);
		addParent(Table.SINGLE_TESTS, Table.RELATIONAL_TESTS);
		addParent(Table.CONSTANTS, Table.SINGLE_TESTS);
		addParent(Table.CONSTANTS, Table.DISJUNCTION_TESTS);
		addParent(Table.CONSTANTS, Table.RHS_VALUES);
		addParent(Table.ACTIONS, Table.RULES);
		addParent(Table.VAR_ATTR_VAL_MAKES, Table.ACTIONS);
		addParent(Table.ATTRIBUTE_VALUE_MAKES, Table.VAR_ATTR_VAL_MAKES);
		addParent(Table.FUNCTION_CALLS, Table.ACTIONS);
		addParent(Table.FUNCTION_NAMES, Table.FUNCTION_CALLS);
		addParent(Table.FUNCTION_CALLS, Table.RHS_VALUES);
		addParent(Table.RHS_VALUES, Table.FUNCTION_CALLS);
		addParent(Table.RHS_VALUES, Table.VALUE_MAKES);
		addParent(Table.RHS_VALUES, Table.ATTRIBUTE_VALUE_MAKES);
		addParent(Table.VALUE_MAKES, Table.ATTRIBUTE_VALUE_MAKES);
		addParent(Table.PREFERENCES, Table.VALUE_MAKES);
		addParent(Table.PREFERENCE_SPECIFIERS, Table.PREFERENCES);
		addParent(Table.NATURALLY_UNARY_PREFERENCES, Table.PREFERENCE_SPECIFIERS);
		addParent(Table.BINARY_PREFERENCES, Table.PREFERENCE_SPECIFIERS);
		addParent(Table.BINARY_PREFERENCES, Table.FORCED_UNARY_PREFERENCES);
		addParent(Table.FORCED_UNARY_PREFERENCES, Table.PREFERENCE_SPECIFIERS);

		// datamap structure
		addParent(Table.DATAMAP_ATTRIBUTES, Table.PROBLEM_SPACES);

		// Automatic children
		ArrayList<Table> children = new ArrayList<Table>();
		children.add(Table.DATAMAP_ATTRIBUTES);
		automaticChildren.put(Table.PROBLEM_SPACES, children);

		// Table / ast object pairs
		tableForAstNode.put(SoarProductionAst.class, Table.RULES);
		tableForAstNode.put(Condition.class, Table.CONDITIONS);
		tableForAstNode.put(PositiveCondition.class, Table.POSITIVE_CONDITIONS);
		tableForAstNode.put(ConditionForOneIdentifier.class, Table.CONDITION_FOR_ONE_IDENTIFIERS);
		tableForAstNode.put(AttributeValueTest.class, Table.ATTRIBUTE_VALUE_TESTS);
		tableForAstNode.put(AttributeTest.class, Table.ATTRIBUTE_TESTS);
		tableForAstNode.put(ValueTest.class, Table.VALUE_TESTS);
		tableForAstNode.put(Test.class, Table.TESTS);
		tableForAstNode.put(ConjunctiveTest.class, Table.CONJUNCTIVE_TESTS);
		tableForAstNode.put(SimpleTest.class, Table.SIMPLE_TESTS);
		tableForAstNode.put(DisjunctionTest.class, Table.DISJUNCTION_TESTS);
		tableForAstNode.put(RelationalTest.class, Table.RELATIONAL_TESTS);

		// No ast class 'Relation'
		// tableForAstElement.put(Relation.class, Table.RELTAIONS);

		tableForAstNode.put(SingleTest.class, Table.SINGLE_TESTS);
		tableForAstNode.put(Constant.class, Table.CONSTANTS);
		tableForAstNode.put(Action.class, Table.ACTIONS);
		tableForAstNode.put(VarAttrValMake.class, Table.VAR_ATTR_VAL_MAKES);
		tableForAstNode.put(AttributeValueMake.class, Table.ATTRIBUTE_VALUE_MAKES);
		tableForAstNode.put(FunctionCall.class, Table.FUNCTION_CALLS);

		// no ast class 'FunctionName'
		// tableForAstElement.put(FunctionName.class, Table.FUNCTION_NAMES);

		tableForAstNode.put(RHSValue.class, Table.RHS_VALUES);
		tableForAstNode.put(ValueMake.class, Table.VALUE_MAKES);

		// No ast class 'Preference'
		// tableForAstElement.put(Preference.class, Table.PREFERENCES);

		tableForAstNode.put(PreferenceSpecifier.class,
				Table.PREFERENCE_SPECIFIERS);
		tableForAstNode.put(NaturallyUnaryPreference.class,
				Table.NATURALLY_UNARY_PREFERENCES);
		tableForAstNode.put(BinaryPreference.class, Table.BINARY_PREFERENCES);
		tableForAstNode.put(ForcedUnaryPreference.class,
				Table.FORCED_UNARY_PREFERENCES);

		initted = true;
	}

	/**
	 * Indicate that table child has foreign key parent_id
	 * 
	 * @param child
	 *            The child table
	 * @param parent
	 *            The parent table
	 */
	private static void addParent(Table child, Table parent) {
		if (!parentTables.keySet().contains(child)) {
			ArrayList<Table> newList = new ArrayList<Table>();
			parentTables.put(child, newList);
		}
		ArrayList<Table> parents = parentTables.get(child);
		parents.add(parent);
		addChild(parent, child);
	}

	private static void addChild(Table parent, Table child) {
		if (!childTables.keySet().contains(parent)) {
			ArrayList<Table> newList = new ArrayList<Table>();
			childTables.put(parent, newList);
		}
		ArrayList<Table> children = childTables.get(parent);
		children.add(child);
	}

	/**
	 * Order of parameters should match the order in the name of the sql table.
	 * 
	 * @param first
	 * @param second
	 */
	private static void joinTables(Table first, Table second) {
		ArrayList<Table> list;

		if (joinedTables.containsKey(first)) {
			list = joinedTables.get(first);
		} else {
			list = new ArrayList<Table>();
		}
		if (!list.contains(second)) {
			list.add(second);
		}
		joinedTables.put(first, list);
	}

	/**
	 * Order of parameters shouldn't matter.
	 * 
	 * @param first
	 * @param second
	 * @return
	 */
	public static boolean tablesAreJoined(Table first, Table second) {
		if (joinedTables.containsKey(first)) {
			ArrayList<Table> list = joinedTables.get(first);
			if (list.contains(second)) {
				return true;
			}
		}
		if (joinedTables.containsKey(second)) {
			ArrayList<Table> list = joinedTables.get(second);
			if (list.contains(first)) {
				return true;
			}
		}
		return false;
	}
	
	public static boolean tablesAreDirectedJoined(Table parent, Table child) {
		if (directedJoinedTables.containsKey(parent)) {
			if (directedJoinedTables.get(parent).contains(child)) {
				return true;
			}
		}
		return false;
	}
	
	/**
	 * Order of parameters should match the order in the name of the sql table.
	 * 
	 * @param first
	 * @param second
	 */
	private static void directedJoinTables(Table parent, Table child) {
		ArrayList<Table> list;

		if (directedJoinedTables.containsKey(parent)) {
			list = directedJoinedTables.get(parent);
		} else {
			list = new ArrayList<Table>();
		}
		if (!list.contains(child)) {
			list.add(child);
		}
		directedJoinedTables.put(parent, list);
	}

	/**
	 * Returns a list of all tables that are joined to the given table (undirected).
	 * 
	 * @param table
	 * @return
	 */
	public static ArrayList<Table> getTablesJoinedToTable(Table table) {
		ArrayList<Table> ret = new ArrayList<Table>();
		for (Table key : joinedTables.keySet()) {
			ArrayList<Table> list = joinedTables.get(key);
			if (key == table) {
				ret.addAll(list);
			} else if (list.contains(table)) {
				ret.add(key);
			}
		}
		return ret;
	}
	
	/**
	 * Returns a list of all tables which are child tables of this table by directed joins.
	 * 
	 * @param table
	 * @return
	 */
	public static ArrayList<Table> getChildTablesDirectedJoinedToTable(Table table) {
		if (directedJoinedTables.containsKey(table)) {
			ArrayList<Table> ret = directedJoinedTables.get(table);
			return ret;
		}
		return new ArrayList<Table>();
	}
	
	// I'm counting on directedJoinedTables being pretty small, so the inefficiency of this
	// method won't hurt too much.
	public static ArrayList<Table> getParentTablesDirectedJoinedToTable(Table table) {
		ArrayList<Table> ret = new ArrayList<Table>();
		for (Table t : directedJoinedTables.keySet()) {
			if (directedJoinedTables.get(t).contains(table)) {
				ret.add(t);
			}
		}
		return ret;
	}

	/**
	 * Determine if the two rows are joined (undirected).
	 * 
	 * @param first
	 *            The first row.
	 * @param second
	 *            The second row.
	 * @param db
	 *            The database connection.
	 * @return True if the rows are joined, otherwise false.
	 */
	public static boolean rowsAreJoined(SoarDatabaseRow first,
			SoarDatabaseRow second, SoarDatabaseConnection db) {

		String joinTableName = joinTableName(first.table, second.table);
		if (joinTableName == null) {
			// The tables are not joined.
			return false;
		}
		
		Table[] orderedTables = orderJoinedTables(first.table, second.table);
		String firstTableIdName = orderedTables[0] == first.table ? "first_id" : "second_id";
		String secondTableIdName = orderedTables[0] == second.table ? "first_id" : "second_id";
		
		String sql = "select * from " + joinTableName + " where " + firstTableIdName + "=? and " + secondTableIdName + "=?";

		StatementWrapper ps = db.prepareStatement(sql);
		boolean ret = false;
		
		try {
			ps.setInt(1, first.id);
			ps.setInt(2, second.id);
			ResultSet rs = ps.executeQuery();
			if (rs.next()) {
				ret = true;
			}
			ps.close();
		} catch (SQLException e) {
			e.printStackTrace();
		}

		return ret;
	}

	/**
	 * Returns the tables in the order they jave been declared joined (the order
	 * their sql join table is named), for use in constructing sql queries.
	 * <p>
	 * Returns null if the tables aren't joined.
	 * 
	 * @param first
	 * @param second
	 * @return
	 */
	public static Table[] orderJoinedTables(Table first, Table second) {
		if (joinedTables.containsKey(first)) {
			if (joinedTables.get(first).contains(second)) {
				return new Table[] { first, second };
			}
		}
		if (joinedTables.containsKey(second)) {
			if (joinedTables.get(second).contains(first)) {
				return new Table[] { second, first };
			}
		}
		return null;
	}

	/**
	 * Returns the properly ordered name of the (unordered) join table.
	 * 
	 * @param first
	 * @param second
	 * @return
	 */
	public static String joinTableName(Table first, Table second) {
		Table[] tables = orderJoinedTables(first, second);

		// This should only be called on two tables that are joinable.
		if (tables == null) {
			return null;
		}

		return "join_" + tables[0].tableName() + "_" + tables[1].tableName();
	}
	
	public static String directedJoinTableName(Table parent, Table child) {
		return "directed_join_" + parent.tableName() + "_" + child.tableName();
	}
	
	public static void addEditableColumnToTable(Table table, EditableColumn column) {
		ArrayList<EditableColumn> columns = null;
		if (editableColumns.containsKey(table)) {
			columns = editableColumns.get(table);
		}
		if (columns == null) {
			columns = new ArrayList<EditableColumn>();
			editableColumns.put(table, columns);
		}
		columns.add(column);
	}

	@Override
	public boolean equals(Object other) {
		if (other instanceof SoarDatabaseRow) {
			SoarDatabaseRow otherRow = (SoarDatabaseRow) other;
			if (otherRow.table == this.table && otherRow.id == this.id) {
				return true;
			}
		}
		return false;
	}

	@Override
	public int hashCode() {
		return id;
	}

	public IEditorInput getEditorInput() {
		return new SoarDatabaseEditorInput(this);
	}

	public boolean exists() {
		String sql = "select * from " + getTable().tableName() + " where id=?";
		StatementWrapper ps = db.prepareStatement(sql);
		boolean ret = false;
		try {
			ps.setInt(1, id);
			ResultSet rs = ps.executeQuery();
			ret = rs.next();
			ps.close();
		} catch (SQLException e) {
			e.printStackTrace();
		}
		return ret;
	}

	public String getText() {
		String ret = null;
		if (table == Table.RULES) {
			String sql = "select (raw_text) from " + table.tableName()
					+ " where id=?";
			StatementWrapper ps = db.prepareStatement(sql);
			try {
				ps.setInt(1, id);
				ResultSet rs = ps.executeQuery();
				if (rs.next()) {
					ret = rs.getString("raw_text");
				}
				ps.close();
			} catch (SQLException e) {
				e.printStackTrace();
			}
		}
		if (ret == null) {
			ret = "";
		}
		return ret;
	}

	public void save(IDocument doc) {
		// TODO Auto-generated method stub
		if (table == Table.RULES) {

			// Update raw text.
			String text = doc.get();
			String sql = "update " + table.tableName() + " set raw_text=? where id=?";
			StatementWrapper ps = db.prepareStatement(sql);

			ps.setString(1, text);
			ps.setInt(2, id);
			ps.execute();

			// Try to get AST from the text.
			// Make sure text starts with "sp {" and ends with "}",
			// but parse only the text inside that.
			// <hacky>
			boolean error = false;
			text = text.trim();
			if (!text.startsWith("sp")) {
				error = true;
			} else {
				text = text.substring(2).trim();
				if (!text.startsWith("{")) {
					error = true;
				}

				else {
					text = text.substring(1).trim();
					if (!text.endsWith("}")) {
						error = true;
					} else {
						int endIndex = text.length() - 1;
						text = text.substring(0, endIndex);
					}
				}
			}
			// </hacky>

			if (!error) {
				// Parse the rule into an AST.
				StringReader reader = new StringReader(text);
				SoarParser parser = new SoarParser(reader);
				try {
					SoarProductionAst ast = parser.soarProduction();
					System.out.println("Parsed rule:\n" + ast);

					// insert into database
					boolean eventsWereSupresssed = db.getSupressEvents();
					db.setSupressEvents(true);
					deleteAllChildren(false);
					try {
						createChildrenFromAstNode(ast);
					} catch (Exception e) {
						// TODO Auto-generated catch block
						e.printStackTrace();
					}
					db.setSupressEvents(eventsWereSupresssed);
					db.fireEvent(new SoarDatabaseEvent(Type.DATABASE_CHANGED));

				} catch (ParseException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
			} else {
				System.out
						.println("Production doesn't begin with \"sp {\" or doesn't end with \"}\"");
			}
		}
	}

	public SoarDatabaseConnection getDatabaseConnection() {
		return db;
	}
}