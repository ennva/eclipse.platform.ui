/*******************************************************************************
 * Copyright (c) 2010, 2012 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     IBM Corporation - initial API and implementation
 ******************************************************************************/

package org.eclipse.ui.internal.handlers;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import javax.inject.Named;
import org.eclipse.core.commands.Command;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.core.commands.HandlerEvent;
import org.eclipse.core.commands.IHandler;
import org.eclipse.core.commands.NotEnabledException;
import org.eclipse.core.commands.NotHandledException;
import org.eclipse.core.commands.ParameterizedCommand;
import org.eclipse.core.commands.common.NotDefinedException;
import org.eclipse.core.expressions.ElementHandler;
import org.eclipse.core.expressions.EvaluationContext;
import org.eclipse.core.expressions.Expression;
import org.eclipse.core.expressions.ExpressionConverter;
import org.eclipse.core.expressions.IEvaluationContext;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IConfigurationElement;
import org.eclipse.core.runtime.IExtensionPoint;
import org.eclipse.core.runtime.IExtensionRegistry;
import org.eclipse.e4.core.commands.ECommandService;
import org.eclipse.e4.core.commands.EHandlerService;
import org.eclipse.e4.core.commands.internal.HandlerServiceImpl;
import org.eclipse.e4.core.contexts.ContextFunction;
import org.eclipse.e4.core.contexts.EclipseContextFactory;
import org.eclipse.e4.core.contexts.IEclipseContext;
import org.eclipse.e4.core.di.InjectionException;
import org.eclipse.e4.core.di.annotations.Execute;
import org.eclipse.e4.core.di.annotations.Optional;
import org.eclipse.e4.ui.internal.workbench.Activator;
import org.eclipse.e4.ui.internal.workbench.Policy;
import org.eclipse.e4.ui.workbench.modeling.ExpressionContext;
import org.eclipse.swt.widgets.Event;
import org.eclipse.ui.ISourceProvider;
import org.eclipse.ui.ISources;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.handlers.IHandlerActivation;
import org.eclipse.ui.handlers.IHandlerService;
import org.eclipse.ui.internal.MakeHandlersGo;
import org.eclipse.ui.internal.WorkbenchPlugin;
import org.eclipse.ui.internal.e4.compatibility.E4Util;
import org.eclipse.ui.internal.expressions.AndExpression;
import org.eclipse.ui.internal.expressions.WorkbenchWindowExpression;
import org.eclipse.ui.internal.registry.IWorkbenchRegistryConstants;
import org.eclipse.ui.internal.services.EvaluationService;
import org.eclipse.ui.services.IEvaluationService;
import org.eclipse.ui.services.ISourceProviderService;

/**
 * @since 3.5
 * 
 */
public class LegacyHandlerService implements IHandlerService {

	private static final String[] SELECTION_VARIABLES = { ISources.ACTIVE_CURRENT_SELECTION_NAME,
			ISources.ACTIVE_FOCUS_CONTROL_ID_NAME, ISources.ACTIVE_FOCUS_CONTROL_NAME,
			ISources.ACTIVE_MENU_EDITOR_INPUT_NAME, ISources.ACTIVE_MENU_NAME,
			ISources.ACTIVE_MENU_SELECTION_NAME };

	public final static String LEGACY_H_ID = "legacy::handler::"; //$NON-NLS-1$

	static class HandlerSelectionFunction extends ContextFunction {

		private final String commandId;

		/**
		 * 
		 */
		public HandlerSelectionFunction(String commandId) {
			this.commandId = commandId;
		}

		@Override
		public Object compute(IEclipseContext context) {

			HashSet<HandlerActivation> activationSet = new HashSet<HandlerActivation>();
			IEclipseContext current = context;
			while (current != null) {
				List<HandlerActivation> handlerActivations = (List<HandlerActivation>) current
						.getLocal(LEGACY_H_ID + commandId);
				if (handlerActivations != null) {
					activationSet.addAll(handlerActivations);
				}
				current = current.getParent();
			}

			if (activationSet.isEmpty()) {
				return null;
			}

			HandlerActivation bestActivation = null;

			ExpressionContext legacyEvalContext = new ExpressionContext(context);

			HandlerActivation conflictBest = null;
			HandlerActivation conflictOther = null;
			for (HandlerActivation handlerActivation : activationSet) {
				if (!handlerActivation.participating)
					continue;
				if (handlerActivation.evaluate(legacyEvalContext)) {
					if (bestActivation == null) {
						bestActivation = handlerActivation;
					} else {
						int comparison = bestActivation.compareTo(handlerActivation);
						if (comparison < 0) {
							bestActivation = handlerActivation;
						} else if (comparison == 0) {
							conflictBest = bestActivation;
							conflictOther = handlerActivation;
						}
					}
				}
			}

			if (bestActivation != null) {
				if (bestActivation == conflictBest) {
					WorkbenchPlugin.log("Conflicting handlers for " + commandId + ": {" //$NON-NLS-1$ //$NON-NLS-2$
							+ conflictBest.getHandler() + "} vs {" //$NON-NLS-1$
							+ conflictOther.getHandler() + "}"); //$NON-NLS-1$
				}
				return bestActivation.proxy;
			}

			return null;
		}
	}

	private static IHandlerActivation systemHandlerActivation;

	/*
	 * We are obligated to return a non-null IHandlerActivation from our
	 * activate calls. It is only used as a token, but must not return null from
	 * certain methods. This token represents passing the MakeHandlerGo handler
	 * back into the system.
	 */
	private static IHandlerActivation getSystemHandlerActivation(IEclipseContext context, final String cmdId) {
		if (systemHandlerActivation == null) {
			final IWorkbench wb = context.get(IWorkbench.class);

			systemHandlerActivation = new IHandlerActivation() {

				public int compareTo(Object o) {
					return -1;
				}

				public void setResult(boolean result) {
				}

				public int getSourcePriority() {
					return 0;
				}

				public Expression getExpression() {
					return null;
				}

				public boolean evaluate(IEvaluationContext context) {
					return false;
				}

				public void clearResult() {
				}

				public boolean isActive(IEvaluationContext context) {
					return false;
				}

				public IHandlerService getHandlerService() {
					return (IHandlerService) wb.getService(IHandlerService.class);
				}

				public IHandler getHandler() {
					return null;
				}

				public int getDepth() {
					return 0;
				}

				public String getCommandId() {
					return cmdId;
				}

				public void clearActive() {
				}
			};
		}
		return systemHandlerActivation;
	}

	public static IHandlerActivation registerLegacyHandler(final IEclipseContext context,
			String id, final String cmdId, IHandler handler, Expression activeWhen) {
		if (handler instanceof MakeHandlersGo) {
			final String msg = "Invalid Handler MakeHandlerGo"; //$NON-NLS-1$
			WorkbenchPlugin.log(msg, new Exception(msg));
			return getSystemHandlerActivation(context, cmdId);
		}
		ECommandService cs = (ECommandService) context.get(ECommandService.class.getName());
		Command command = cs.getCommand(cmdId);
		boolean handled = command.isHandled();
		boolean enabled = command.isEnabled();
		E4HandlerProxy handlerProxy = new E4HandlerProxy(command, handler);
		HandlerActivation activation = new HandlerActivation(context, cmdId, handler, handlerProxy,
				activeWhen);
		addHandlerActivation(activation);
		EHandlerService hs = context.get(EHandlerService.class);
		hs.activateHandler(cmdId, new HandlerSelectionFunction(cmdId));
		boolean handledChanged = handled != command.isHandled();
		boolean enabledChanged = enabled != command.isEnabled();
		if (handledChanged || enabledChanged) {
			IHandler proxy = command.getHandler();
			if (proxy instanceof MakeHandlersGo) {
				((MakeHandlersGo) proxy).fireHandlerChanged(new HandlerEvent(proxy, enabledChanged,
						handledChanged));
			}
		}
		return activation;
	}

	static void addHandlerActivation(HandlerActivation eActivation) {
		List handlerActivations = (List) eActivation.context.getLocal(LEGACY_H_ID
				+ eActivation.getCommandId());
		if (handlerActivations == null) {
			handlerActivations = new ArrayList();
		} else {
			if (handlerActivations.contains(eActivation)) {
				return;
			}
			handlerActivations = new ArrayList(handlerActivations);
		}
		handlerActivations.add(eActivation);
		// setting this so that we trigger invalidations
		eActivation.context.set(LEGACY_H_ID + eActivation.getCommandId(), handlerActivations);
	}

	static void removeHandlerActivation(HandlerActivation eActivation) {
		List handlerActivations = (List) eActivation.context.getLocal(LEGACY_H_ID
				+ eActivation.getCommandId());
		if (handlerActivations == null) {
			handlerActivations = new ArrayList();
		} else {
			handlerActivations = new ArrayList(handlerActivations);
		}
		handlerActivations.remove(eActivation);
		// setting this so that we trigger invalidations
		eActivation.context.set(LEGACY_H_ID + eActivation.getCommandId(), handlerActivations);
	}

	private IEclipseContext eclipseContext;
	private IEvaluationContext evalContext;
	private Expression defaultExpression = null;

	public LegacyHandlerService(IEclipseContext context) {
		eclipseContext = context;
		evalContext = new ExpressionContext(eclipseContext);
		IWorkbenchWindow window = (IWorkbenchWindow) eclipseContext.get(IWorkbenchWindow.class
				.getName());
		if (window != null) {
			defaultExpression = new WorkbenchWindowExpression(window);
		}
	}

	public LegacyHandlerService(IEclipseContext context, Expression defaultExpression) {
		eclipseContext = context;
		evalContext = new ExpressionContext(eclipseContext);
		this.defaultExpression = defaultExpression;
	}

	public void initPreExecuteHook() {
		EHandlerService hs = eclipseContext.get(EHandlerService.class);
		if (hs instanceof HandlerServiceImpl) {
			HandlerServiceImpl.preExecute = new Object() {
				@Execute
				public void execute(IEclipseContext context, ParameterizedCommand command,
						@Optional @Named(HandlerServiceImpl.PARM_MAP) Map parms,
						@Optional Event trigger, @Optional IEvaluationContext staticContext) {
					if (command == null) {
						return;
					}
					IEvaluationContext appContext = staticContext;
					if (appContext == null) {
						appContext = new ExpressionContext(context);
					}
					ExecutionEvent event = new ExecutionEvent(command.getCommand(), parms, trigger,
							appContext);
					CommandProxy.firePreExecute(command.getCommand(), event);
				}
			};
		}

	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see
	 * org.eclipse.ui.services.IServiceWithSources#addSourceProvider(org.eclipse
	 * .ui.ISourceProvider)
	 */
	public void addSourceProvider(ISourceProvider provider) {
		// TODO Auto-generated method stub

	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see
	 * org.eclipse.ui.services.IServiceWithSources#removeSourceProvider(org.
	 * eclipse.ui.ISourceProvider)
	 */
	public void removeSourceProvider(ISourceProvider provider) {
		// TODO Auto-generated method stub

	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.eclipse.ui.services.IDisposable#dispose()
	 */
	public void dispose() {
		E4Util.message("LegacyHandlerService.dispose: should it do something?"); //$NON-NLS-1$
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see
	 * org.eclipse.ui.handlers.IHandlerService#activateHandler(org.eclipse.ui
	 * .handlers.IHandlerActivation)
	 */
	public IHandlerActivation activateHandler(IHandlerActivation activation) {
		if (activation == systemHandlerActivation) {
			return activation;
		}
		HandlerActivation handlerActivation = (HandlerActivation) activation;
		handlerActivation.participating = true;
		addHandlerActivation(handlerActivation);
		return activation;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see
	 * org.eclipse.ui.handlers.IHandlerService#activateHandler(java.lang.String,
	 * org.eclipse.core.commands.IHandler)
	 */
	public IHandlerActivation activateHandler(String commandId, IHandler handler) {
		return activateHandler(commandId, handler, defaultExpression, false);
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see
	 * org.eclipse.ui.handlers.IHandlerService#activateHandler(java.lang.String,
	 * org.eclipse.core.commands.IHandler,
	 * org.eclipse.core.expressions.Expression)
	 */
	public IHandlerActivation activateHandler(String commandId, IHandler handler,
			Expression expression) {
		return activateHandler(commandId, handler, expression, false);
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see
	 * org.eclipse.ui.handlers.IHandlerService#activateHandler(java.lang.String,
	 * org.eclipse.core.commands.IHandler,
	 * org.eclipse.core.expressions.Expression, boolean)
	 */
	public IHandlerActivation activateHandler(String commandId, IHandler handler,
			Expression expression, boolean global) {
		if (global || defaultExpression == null) {
			return registerLegacyHandler(eclipseContext, commandId, commandId, handler, expression);
		}
		AndExpression andExpr = new AndExpression();
		andExpr.add(expression);
		andExpr.add(defaultExpression);
		return registerLegacyHandler(eclipseContext, commandId, commandId, handler, andExpr);
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see
	 * org.eclipse.ui.handlers.IHandlerService#activateHandler(java.lang.String,
	 * org.eclipse.core.commands.IHandler,
	 * org.eclipse.core.expressions.Expression, int)
	 */
	public IHandlerActivation activateHandler(String commandId, IHandler handler,
			Expression expression, int sourcePriorities) {
		return activateHandler(commandId, handler, expression, false);
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see
	 * org.eclipse.ui.handlers.IHandlerService#createExecutionEvent(org.eclipse
	 * .core.commands.Command, org.eclipse.swt.widgets.Event)
	 */
	public ExecutionEvent createExecutionEvent(Command command, Event event) {
		EvaluationContext legacy = new EvaluationContext(evalContext,
				evalContext.getDefaultVariable());
		ExecutionEvent e = new ExecutionEvent(command, Collections.EMPTY_MAP, event, legacy);
		return e;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see
	 * org.eclipse.ui.handlers.IHandlerService#createExecutionEvent(org.eclipse
	 * .core.commands.ParameterizedCommand, org.eclipse.swt.widgets.Event)
	 */
	public ExecutionEvent createExecutionEvent(ParameterizedCommand command, Event event) {
		EvaluationContext legacy = new EvaluationContext(evalContext,
				evalContext.getDefaultVariable());
		ExecutionEvent e = new ExecutionEvent(command.getCommand(), command.getParameterMap(),
				event, legacy);
		return e;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see
	 * org.eclipse.ui.handlers.IHandlerService#deactivateHandler(org.eclipse
	 * .ui.handlers.IHandlerActivation)
	 */
	public void deactivateHandler(IHandlerActivation activation) {
		// null is not allowed, but some people put it anyway :( see bug 326406
		if (activation != null && activation != systemHandlerActivation) {
			HandlerActivation eActivation = (HandlerActivation) activation;
			eActivation.participating = false;
			removeHandlerActivation(eActivation);
		}
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see
	 * org.eclipse.ui.handlers.IHandlerService#deactivateHandlers(java.util.
	 * Collection)
	 */
	public void deactivateHandlers(Collection activations) {
		Object[] array = activations.toArray();
		// set all activations to not be participating first so that they ignore
		// the upcoming context change events
		for (int i = 0; i < array.length; i++) {
			((HandlerActivation) array[i]).participating = false;
		}

		for (int i = 0; i < array.length; i++) {
			deactivateHandler((IHandlerActivation) array[i]);
		}
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see
	 * org.eclipse.ui.handlers.IHandlerService#executeCommand(java.lang.String,
	 * org.eclipse.swt.widgets.Event)
	 */
	public Object executeCommand(String commandId, Event event) throws ExecutionException,
			NotDefinedException, NotEnabledException, NotHandledException {
		ECommandService cs = eclipseContext.get(ECommandService.class);
		final Command command = cs.getCommand(commandId);
		return executeCommand(ParameterizedCommand.generateCommand(command, null), event);
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see
	 * org.eclipse.ui.handlers.IHandlerService#executeCommand(org.eclipse.core
	 * .commands.ParameterizedCommand, org.eclipse.swt.widgets.Event)
	 */
	public Object executeCommand(ParameterizedCommand command, Event event)
			throws ExecutionException, NotDefinedException, NotEnabledException,
			NotHandledException {
		EHandlerService hs = eclipseContext.get(EHandlerService.class);
		IEclipseContext staticContext = EclipseContextFactory.create();
		if (event != null) {
			staticContext.set(Event.class, event);
		}
		try {
			final Object rc = hs.executeHandler(command, staticContext);
			if (staticContext.get(HandlerServiceImpl.NOT_HANDLED) == Boolean.TRUE) {
				final NotHandledException e = new NotHandledException(
						"There is no handler to execute for command " + command.getId()); //$NON-NLS-1$
				CommandProxy.fireNotHandled(command.getCommand(), e);
				throw e;
			}
			final Object obj = staticContext.get(HandlerServiceImpl.CAN_EXECUTE);
			if (obj instanceof Boolean) {
				if (!((Boolean) obj).booleanValue()) {
					final NotEnabledException exception = new NotEnabledException(
							"Trying to execute the disabled command " + command.getId()); //$NON-NLS-1$
					CommandProxy.fireNotEnabled(command.getCommand(), exception);
					throw exception;
				}
			}
			return rc;
		} catch (InjectionException e) {
			rethrow(e);
			throw e;
		} finally {
			staticContext.dispose();
		}
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see
	 * org.eclipse.ui.handlers.IHandlerService#executeCommandInContext(org.eclipse
	 * .core.commands.ParameterizedCommand, org.eclipse.swt.widgets.Event,
	 * org.eclipse.core.expressions.IEvaluationContext)
	 */
	public Object executeCommandInContext(ParameterizedCommand command, Event event,
			IEvaluationContext context) throws ExecutionException, NotDefinedException,
			NotEnabledException, NotHandledException {

		IEclipseContext staticContext = null;
		Object defaultVar = null;
		if (context instanceof ExpressionContext) {
			// create a child context so that the primary context doesn't get
			// populated by parameters by the EHS
			staticContext = ((ExpressionContext) context).eclipseContext.createChild();
		} else {
			staticContext = eclipseContext.getActiveLeaf().createChild("snapshotContext"); //$NON-NLS-1$
			if (event != null) {
				staticContext.set(Event.class, event);
			}
			staticContext.set(IEvaluationContext.class, context);
			defaultVar = context.getDefaultVariable();
			if (defaultVar != null && defaultVar != IEvaluationContext.UNDEFINED_VARIABLE) {
				staticContext.set(EvaluationService.DEFAULT_VAR, defaultVar);
			}
		}
		IEclipseContext lookupContext = staticContext;
		// the IEvaluationContext snapshot is not part of our runtime
		// IEclipseContext hierarchy. In order to work. we need the variables
		// from the snapshot available in the static context.
		populateSnapshot(context, staticContext);
		EHandlerService hs = lookupContext.get(EHandlerService.class);
		try {
			final Object rc = hs.executeHandler(command, staticContext);
			if (staticContext.get(HandlerServiceImpl.NOT_HANDLED) == Boolean.TRUE) {
				final NotHandledException e = new NotHandledException(
						"There is no handler to execute for command " + command.getId()); //$NON-NLS-1$
				CommandProxy.fireNotHandled(command.getCommand(), e);
				throw e;
			}
			final Object obj = staticContext.get(HandlerServiceImpl.CAN_EXECUTE);
			if (obj instanceof Boolean) {
				if (!((Boolean) obj).booleanValue()) {
					final NotEnabledException exception = new NotEnabledException(
							"Trying to execute the disabled command " + command.getId()); //$NON-NLS-1$
					CommandProxy.fireNotEnabled(command.getCommand(), exception);
					throw exception;
				}
			}
			return rc;
		} catch (InjectionException e) {
			rethrow(e);
			throw e;
		} finally {
			staticContext.dispose();
		}
	}

	/**
	 * @param context
	 * @param staticContext
	 */
	private void populateSnapshot(IEvaluationContext context, IEclipseContext staticContext) {
		IEvaluationContext ctxPtr = context;
		while (ctxPtr != null && !(ctxPtr instanceof ExpressionContext)) {
			Map vars = getVariables(ctxPtr);
			if (vars != null) {
				Iterator i = vars.entrySet().iterator();
				while (i.hasNext()) {
					Map.Entry entry = (Map.Entry) i.next();
					if (staticContext.getLocal(entry.getKey().toString()) == null) {
						staticContext.set(entry.getKey().toString(), entry.getValue());
					}
				}
			}
			ctxPtr = ctxPtr.getParent();
		}
	}

	private Map getVariables(IEvaluationContext ctx) {
		Field vars = getContextVariablesField();
		if (vars != null) {
			try {
				return (Map) vars.get(ctx);
			} catch (IllegalArgumentException e) {

			} catch (IllegalAccessException e) {

			}
		}
		return null;
	}

	private static Field contextFVariables = null;

	private static Field getContextVariablesField() {
		if (contextFVariables == null) {
			try {
				contextFVariables = EvaluationContext.class.getField("fVariables"); //$NON-NLS-1$
				contextFVariables.setAccessible(true);
			} catch (SecurityException e) {

			} catch (NoSuchFieldException e) {

			}
		}
		return contextFVariables;
	}

	/**
	 * Checks the cause of the provided exception and rethrows the cause instead
	 * if it was one of the expected exception types.
	 */
	private void rethrow(InjectionException e) throws ExecutionException, NotDefinedException,
			NotEnabledException, NotHandledException {
		Throwable cause = e.getCause();
		if (cause instanceof ExecutionException) {
			throw (ExecutionException) cause;
		} else if (cause instanceof NotDefinedException) {
			throw (NotDefinedException) cause;
		} else if (cause instanceof NotEnabledException) {
			throw (NotEnabledException) cause;
		} else if (cause instanceof NotHandledException) {
			throw (NotHandledException) cause;
		}
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see
	 * org.eclipse.ui.handlers.IHandlerService#createContextSnapshot(boolean)
	 */
	public IEvaluationContext createContextSnapshot(boolean includeSelection) {
		IEvaluationContext tmpContext = getCurrentState();
		IEvaluationContext context = new EvaluationContext(null,
				IEvaluationContext.UNDEFINED_VARIABLE);

		if (includeSelection) {
			for (String variable : SELECTION_VARIABLES) {
				copyVariable(context, tmpContext, variable);
			}
		}

		ISourceProviderService sp = eclipseContext.get(ISourceProviderService.class);
		for (ISourceProvider provider : sp.getSourceProviders()) {
			String[] names = provider.getProvidedSourceNames();
			for (String name : names) {
				if (!isSelectionVariable(name)) {
					copyVariable(context, tmpContext, name);
				}
			}
		}

		return context;
	}

	private boolean isSelectionVariable(String name) {
		for (String variable : SELECTION_VARIABLES) {
			if (variable.equals(name)) {
				return true;
			}
		}
		return false;
	}

	private void copyVariable(IEvaluationContext context, IEvaluationContext tmpContext, String var) {
		Object o = tmpContext.getVariable(var);
		if (o != null) {
			context.addVariable(var, o);
		}
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.eclipse.ui.handlers.IHandlerService#getCurrentState()
	 */
	public IEvaluationContext getCurrentState() {
		return new EvaluationContext(evalContext, evalContext.getDefaultVariable());
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.eclipse.ui.handlers.IHandlerService#readRegistry()
	 */
	public void readRegistry() {
		readDefaultHandlers();
		readHandlers();
	}

	private void readHandlers() {
		IExtensionRegistry registry = (IExtensionRegistry) eclipseContext
				.get(IExtensionRegistry.class.getName());
		IExtensionPoint extPoint = registry
				.getExtensionPoint(IWorkbenchRegistryConstants.EXTENSION_HANDLERS);
		IConfigurationElement[] elements = extPoint.getConfigurationElements();
		for (IConfigurationElement configElement : elements) {
			String commandId = configElement
					.getAttribute(IWorkbenchRegistryConstants.ATT_COMMAND_ID);
			if (commandId == null || commandId.length() == 0) {
				continue;
			}
			String defaultHandler = configElement
					.getAttribute(IWorkbenchRegistryConstants.ATT_CLASS);
			if ((defaultHandler == null)
					&& (configElement.getChildren(IWorkbenchRegistryConstants.TAG_CLASS).length == 0)) {
				continue;
			}
			Expression activeWhen = null;
			final IConfigurationElement[] awChildren = configElement
					.getChildren(IWorkbenchRegistryConstants.TAG_ACTIVE_WHEN);
			if (awChildren.length > 0) {
				final IConfigurationElement[] subChildren = awChildren[0].getChildren();
				if (subChildren.length != 1) {
					Activator.trace(Policy.DEBUG_CMDS,
							"Incorrect activeWhen element " + commandId, null); //$NON-NLS-1$
					continue;
				}
				final ElementHandler elementHandler = ElementHandler.getDefault();
				final ExpressionConverter converter = ExpressionConverter.getDefault();
				try {
					activeWhen = elementHandler.create(converter, subChildren[0]);
				} catch (CoreException e) {
					Activator.trace(Policy.DEBUG_CMDS,
							"Incorrect activeWhen element " + commandId, e); //$NON-NLS-1$
				}
			}
			Expression enabledWhen = null;
			final IConfigurationElement[] ewChildren = configElement
					.getChildren(IWorkbenchRegistryConstants.TAG_ENABLED_WHEN);
			if (ewChildren.length > 0) {
				final IConfigurationElement[] subChildren = ewChildren[0].getChildren();
				if (subChildren.length != 1) {
					Activator.trace(Policy.DEBUG_CMDS,
							"Incorrect enableWhen element " + commandId, null); //$NON-NLS-1$
					continue;
				}
				final ElementHandler elementHandler = ElementHandler.getDefault();
				final ExpressionConverter converter = ExpressionConverter.getDefault();
				try {
					enabledWhen = elementHandler.create(converter, subChildren[0]);
				} catch (CoreException e) {
					Activator.trace(Policy.DEBUG_CMDS,
							"Incorrect enableWhen element " + commandId, e); //$NON-NLS-1$
				}
			}
			registerLegacyHandler(
					eclipseContext,
					commandId,
					commandId,
					new org.eclipse.ui.internal.handlers.HandlerProxy(commandId, configElement,
							IWorkbenchRegistryConstants.ATT_CLASS, enabledWhen, eclipseContext
									.get(IEvaluationService.class)), activeWhen);
		}
	}

	private void readDefaultHandlers() {
		IExtensionRegistry registry = (IExtensionRegistry) eclipseContext
				.get(IExtensionRegistry.class.getName());
		IExtensionPoint extPoint = registry
				.getExtensionPoint(IWorkbenchRegistryConstants.EXTENSION_COMMANDS);
		IConfigurationElement[] elements = extPoint.getConfigurationElements();
		for (IConfigurationElement configElement : elements) {
			String id = configElement.getAttribute(IWorkbenchRegistryConstants.ATT_ID);
			if (id == null || id.length() == 0) {
				continue;
			}
			String defaultHandler = configElement
					.getAttribute(IWorkbenchRegistryConstants.ATT_DEFAULT_HANDLER);
			if ((defaultHandler == null)
					&& (configElement.getChildren(IWorkbenchRegistryConstants.TAG_DEFAULT_HANDLER).length == 0)) {
				continue;
			}
			registerLegacyHandler(eclipseContext, id, id,
					new org.eclipse.ui.internal.handlers.HandlerProxy(id, configElement,
							IWorkbenchRegistryConstants.ATT_DEFAULT_HANDLER), null);

		}
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see
	 * org.eclipse.ui.handlers.IHandlerService#setHelpContextId(org.eclipse.
	 * core.commands.IHandler, java.lang.String)
	 */
	public void setHelpContextId(IHandler handler, String helpContextId) {
		// TODO Auto-generated method stub

	}
}
