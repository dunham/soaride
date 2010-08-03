package com.soartech.soar.ide.ui.views.search;

import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.jface.dialogs.ProgressMonitorDialog;
import org.eclipse.jface.operation.IRunnableWithProgress;
import org.eclipse.jface.viewers.ArrayContentProvider;
import org.eclipse.jface.viewers.TableViewer;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.PartInitException;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.part.ViewPart;

import com.soartech.soar.ide.core.SoarCorePlugin;
import com.soartech.soar.ide.core.sql.ISoarDatabaseTreeItem;
import com.soartech.soar.ide.core.sql.SoarDatabaseRow;
import com.soartech.soar.ide.core.sql.SoarDatabaseRow.Table;
import com.soartech.soar.ide.ui.actions.explorer.DatabaseTraversal.TraversalUtil;
import com.soartech.soar.ide.ui.actions.explorer.DatabaseTraversal.Triple;
import com.soartech.soar.ide.ui.views.SoarDatabaseRowDoubleClickListener;
import com.soartech.soar.ide.ui.views.SoarLabelProvider;

public class SoarDatabaseSearchResultsView extends ViewPart {

	public static final String ID = "com.soartech.soar.ide.ui.views.SoarDatabaseSearchResultsView";
	
	TableViewer table;

	@Override
	public void createPartControl(Composite parent) {
		table = new TableViewer(parent);
		table.addDoubleClickListener(new SoarDatabaseRowDoubleClickListener());
		table.setContentProvider(new ArrayContentProvider());
		table.setLabelProvider(SoarLabelProvider.createFullLabelProvider());
		table.setInput(null);
	}

	@Override
	public void setFocus() {
		
	}
	
	public void setSearchResults(Object[] results) {
		table.setInput(results);
	}
	
	private static void setResults(Object[] results) {
		SoarDatabaseSearchResultsView view;
		try {
			view = (SoarDatabaseSearchResultsView) PlatformUI.getWorkbench().getActiveWorkbenchWindow().getActivePage().showView(ID);
			view.setSearchResults(results);
		} catch (PartInitException e) {
			e.printStackTrace();
		}
	}
	
	public static void searchForRulesWithString(String query) {
		ArrayList<SoarDatabaseRow> agents = SoarCorePlugin.getDefault().getDatabaseConnection().selectAllFromTable(Table.AGENTS);
		ArrayList<SoarDatabaseRow> result = new ArrayList<SoarDatabaseRow>();
		for (SoarDatabaseRow agent : agents) {
			ArrayList<SoarDatabaseRow> rules = agent.getChildrenOfType(Table.RULES);
			for (SoarDatabaseRow rule : rules) {
				if (rule.getText().contains(query)) {
					result.add(rule);
				}
			}
		}
		setResults(result.toArray());
	}
	
	public static void searchForRulesWithDatamapAttribute(SoarDatabaseRow attribute) {
		assert attribute.getTable().isDatamapTable();
		String[] fullPath = attribute.getPathName().split("\\.");
		final String[] path = new String[fullPath.length - 1];
		for (int i = 0; i < path.length; ++i) {
			path[i] = fullPath[i + 1];
		}
		SoarDatabaseRow problemSpace = attribute.getAncestorRow(Table.PROBLEM_SPACES);
		final ArrayList<ISoarDatabaseTreeItem> allRules = TraversalUtil.getRelatedRules(problemSpace);
		final ArrayList<SoarDatabaseRow> result = new ArrayList<SoarDatabaseRow>();
		
		Shell shell = PlatformUI.getWorkbench().getActiveWorkbenchWindow().getShell();
		try {
			new ProgressMonitorDialog(shell).run(false, false, new IRunnableWithProgress() {
				@Override
				public void run(IProgressMonitor monitor) throws InvocationTargetException, InterruptedException {
					monitor.beginTask("Searching Rules", allRules.size());
					for (ISoarDatabaseTreeItem item : allRules) {
						SoarDatabaseRow rule = (SoarDatabaseRow) item;
						monitor.subTask(rule.getName());
						for (Triple triple : TraversalUtil.getTriplesForRule(rule)) {
							if (triple.matchesPath(path)) {
								result.add(rule);
								break;
							}
						}
						monitor.worked(1);
					}
					monitor.done();
				}
			});
		} catch (InvocationTargetException e1) {
			e1.printStackTrace();
		} catch (InterruptedException e1) {
			e1.printStackTrace();
		}
		setResults(result.toArray());
	}
}