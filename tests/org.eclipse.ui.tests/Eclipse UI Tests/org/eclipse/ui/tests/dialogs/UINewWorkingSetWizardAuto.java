package org.eclipse.ui.tests.dialogs;

import java.util.ArrayList;
import java.util.List;

import junit.framework.TestCase;
import org.eclipse.core.resources.*;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.IAdaptable;
import org.eclipse.jface.dialogs.Dialog;
import org.eclipse.jface.dialogs.IDialogSettings;
import org.eclipse.jface.viewers.*;
import org.eclipse.jface.wizard.*;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.*;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Table;
import org.eclipse.ui.*;
import org.eclipse.ui.dialogs.IWorkingSetPage;
import org.eclipse.ui.dialogs.WizardNewProjectReferencePage;
import org.eclipse.ui.help.WorkbenchHelp;
import org.eclipse.ui.internal.*;
import org.eclipse.ui.internal.dialogs.*;
import org.eclipse.ui.internal.registry.WorkingSetDescriptor;
import org.eclipse.ui.internal.registry.WorkingSetRegistry;
import org.eclipse.ui.tests.util.*;
import org.eclipse.ui.tests.util.ArrayUtil;
import org.eclipse.ui.tests.util.DialogCheck;
import org.eclipse.ui.wizards.newresource.*;

/**
 * Tests the WorkingSetNewWizard.
 * Tests input validation, presence of type page and correct edit page
 * and wizard page texts.
 */
public class UINewWorkingSetWizardAuto extends UIWorkingSetWizardsAuto {

	public UINewWorkingSetWizardAuto(String name) {
		super(name);
	}
	/**
	 * @see junit.framework.TestCase#setUp()
	 */
	protected void setUp() throws Exception {
		fWizard = new WorkingSetNewWizard();
		super.setUp();
	}
	public void testTypePage() throws Throwable {
		IWizardPage page = fWizardDialog.getCurrentPage();
		assertTrue((page instanceof WorkingSetTypePage) == fWorkingSetDescriptors.length > 1);
		
		/*
		 * Should have at least resourceWorkingSetPage and MockWorkingSet
		 */
		assertTrue(fWorkingSetDescriptors.length >= 2);		
		if (page instanceof WorkingSetTypePage) {
			WorkingSetTypePage typePage = (WorkingSetTypePage) page;
			List widgets = getWidgets(fWizardDialog.getShell(), Table.class);
			Table table = (Table) widgets.get(0);
			/*
			 * Test initial page state
			 */
			assertEquals(fWorkingSetDescriptors.length, table.getItemCount());
			assertTrue(typePage.canFlipToNextPage() == false);
			assertTrue(fWizard.canFinish() == false);						
			/*
			 * Test page state with page complete input
			 */
			table.setSelection(fWorkingSetDescriptors.length - 1);
			table.notifyListeners(SWT.Selection, new Event());
			assertTrue(typePage.canFlipToNextPage());
			assertTrue(fWizard.canFinish() == false);
			
			/*
			 * Check page texts 
			 */
			DialogCheck.assertDialogTexts(fWizardDialog, this);
		}
	}
	public void testEditPage() throws Throwable {
		final int workingSetTypeIndex = fWorkingSetDescriptors.length - 1;
		WorkingSetRegistry registry = WorkbenchPlugin.getDefault().getWorkingSetRegistry();
		IWizardPage page = fWizardDialog.getCurrentPage();
		IWizardPage defaultEditPage = registry.getDefaultWorkingSetPage();
		assertTrue((page instanceof WorkingSetTypePage) == fWorkingSetDescriptors.length > 1);

		if (page instanceof WorkingSetTypePage) {
			/*
			 * Select the default (Resource) working set type
			 * and advance to edit page.
			 */
			WorkingSetTypePage typePage = (WorkingSetTypePage) page;
			List widgets = getWidgets(fWizardDialog.getShell(), Table.class);
			Table table = (Table) widgets.get(0);
			TableItem[] items = table.getItems();
			String workingSetName = null;
			for (int descriptorIndex = 0; descriptorIndex < fWorkingSetDescriptors.length; descriptorIndex++) {
				Class pageClass = Class.forName(fWorkingSetDescriptors[descriptorIndex].getPageClassName());
				if (pageClass == defaultEditPage.getClass()) {
					workingSetName = fWorkingSetDescriptors[descriptorIndex].getName();
					break;
				}
			}
			for (int i = 0; i < items.length; i++) {
				if (items[i].getText().equals(workingSetName)) {
					table.setSelection(i);
					break;
				}
			}
			fWizardDialog.showPage(fWizard.getNextPage(page));
		}
		page = fWizardDialog.getCurrentPage();
		assertTrue(page instanceof IWorkingSetPage);

		/*
		 * Verify that correct working set edit page is displayed
		 */
		assertTrue(page.getClass() == defaultEditPage.getClass());
		/*
		 * Test initial page state
		 */
		assertTrue(page.canFlipToNextPage() == false);
		assertTrue(fWizard.canFinish() == false);						
		assertNull(page.getErrorMessage());
		/*
		 * Test page state with partial page input
		 */
		setTextWidgetText(WORKING_SET_NAME_1);
		assertTrue(page.canFlipToNextPage() == false);
		assertTrue(fWizard.canFinish() == false);		
		assertNotNull(page.getErrorMessage());		

		/*
		 * Test page state with page complete input
		 */
		checkTreeItems();
		assertTrue(page.canFlipToNextPage() == false);
		assertTrue(fWizard.canFinish());
		assertNull(page.getErrorMessage());
		
		fWizard.performFinish();
		IWorkingSet workingSet = ((WorkingSetNewWizard) fWizard).getSelection();
		IAdaptable[] workingSetItems = workingSet.getElements();
		assertEquals(WORKING_SET_NAME_1, workingSet.getName());
		
		List widgets = getWidgets(fWizardDialog.getShell(), Tree.class);
		Tree tree = (Tree) widgets.get(0);
		assertEquals(workingSetItems.length, tree.getItemCount());
		assertTrue(ArrayUtil.contains(workingSetItems, p1));
		assertTrue(ArrayUtil.contains(workingSetItems, p2));

		/*
		 * Check page texts 
		 */
		DialogCheck.assertDialogTexts(fWizardDialog, this);
	}
}

