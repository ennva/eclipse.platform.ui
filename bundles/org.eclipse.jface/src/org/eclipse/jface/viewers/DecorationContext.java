/*******************************************************************************
 * Copyright (c) 2006, 2007 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
package org.eclipse.jface.viewers;

import java.util.HashMap;
import java.util.Map;

import org.eclipse.jface.resource.ResourceManager;

/**
 * A concrete implementation of the {@link IDecorationContext} interface,
 * suitable for instantiating.
 * <p>
 * This class is not intended to be subclassed.
 * </p>
 * @since 3.2
 */
public class DecorationContext implements IDecorationContext {
	
	/**
	 * Constant that defines a default decoration context that has
	 * no context ids associated with it.
	 */
	public static final IDecorationContext DEFAULT_CONTEXT = new DecorationContext();
	
	/**
	 * RESOURCE_MANAGER_KEY is a key for the property of a context that contains
	 * a {@link ResourceManager} for use by the decoration mechanism to cache images.
	 * If a property with this key exists in the {@link DecorationContext} of a 
	 * {@link DecoratingLabelProvider} it will be disposed when the {@link DecoratingLabelProvider}
	 * is disposed.
	 * 
	 * @since 3.4
	 */
	public static final String RESOURCE_MANAGER_KEY = "RESOURCE_MANAGER"; //$NON-NLS-1$
	
	private Map properties = new HashMap();

	/**
	 * Create a decoration context.
	 */
	public DecorationContext() {
	}
	

	/* (non-Javadoc)
	 * @see org.eclipse.jface.viewers.IDecorationContext#getProperty(java.lang.String)
	 */
	public Object getProperty(String property) {
		return properties.get(property);
	}

	/* (non-Javadoc)
	 * @see org.eclipse.jface.viewers.IDecorationContext#getProperties()
	 */
	public String[] getProperties() {
		return (String[]) properties.keySet().toArray(new String[properties.size()]);
	}

	/**
	 * Set the given property to the given value. Setting the value of
	 * a property to <code>null</code> removes the property from
	 * the context.
	 * @param property the property
	 * @param value the value of the property or <code>null</code>
	 * if the property is to be removed.
	 */
	public void putProperty(String property, Object value) {
		if (value == null) {
			properties.remove(property);
		} else {
			properties.put(property, value);
		}
	}
}
