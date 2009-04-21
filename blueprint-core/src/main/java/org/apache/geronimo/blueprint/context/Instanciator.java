/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.geronimo.blueprint.context;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.geronimo.blueprint.namespace.ComponentDefinitionRegistryImpl;
import org.apache.geronimo.blueprint.reflect.ServiceExportComponentMetadataImpl;
import org.apache.xbean.recipe.ArrayRecipe;
import org.apache.xbean.recipe.CollectionRecipe;
import org.apache.xbean.recipe.ConstructionException;
import org.apache.xbean.recipe.DefaultRepository;
import org.apache.xbean.recipe.MapRecipe;
import org.apache.xbean.recipe.ObjectRecipe;
import org.apache.xbean.recipe.Option;
import org.apache.xbean.recipe.Recipe;
import org.apache.xbean.recipe.ReferenceRecipe;
import org.apache.xbean.recipe.Repository;
import org.osgi.service.blueprint.convert.ConversionService;
import org.osgi.service.blueprint.namespace.ComponentDefinitionRegistry;
import org.osgi.service.blueprint.reflect.ArrayValue;
import org.osgi.service.blueprint.reflect.ComponentMetadata;
import org.osgi.service.blueprint.reflect.ComponentValue;
import org.osgi.service.blueprint.reflect.ListValue;
import org.osgi.service.blueprint.reflect.LocalComponentMetadata;
import org.osgi.service.blueprint.reflect.MapValue;
import org.osgi.service.blueprint.reflect.NullValue;
import org.osgi.service.blueprint.reflect.PropertiesValue;
import org.osgi.service.blueprint.reflect.PropertyInjectionMetadata;
import org.osgi.service.blueprint.reflect.ReferenceNameValue;
import org.osgi.service.blueprint.reflect.ReferenceValue;
import org.osgi.service.blueprint.reflect.RegistrationListenerMetadata;
import org.osgi.service.blueprint.reflect.ServiceExportComponentMetadata;
import org.osgi.service.blueprint.reflect.SetValue;
import org.osgi.service.blueprint.reflect.TypedStringValue;
import org.osgi.service.blueprint.reflect.Value;
import org.osgi.service.blueprint.reflect.UnaryServiceReferenceComponentMetadata;
import org.osgi.service.blueprint.reflect.CollectionBasedServiceReferenceComponentMetadata;
import org.osgi.service.blueprint.reflect.BindingListenerMetadata;

/**
 * TODO: javadoc
 *
 * TODO: compound property names
 *
 * @author <a href="mailto:dev@geronimo.apache.org">Apache Geronimo Project</a>
 * @version $Rev: 760378 $, $Date: 2009-03-31 11:31:38 +0200 (Tue, 31 Mar 2009) $
 */
public class Instanciator {

    private static Map<String, Class> primitiveClasses = new HashMap<String, Class>();
    
    static {
        primitiveClasses.put("int", int.class);
        primitiveClasses.put("short", short.class);
        primitiveClasses.put("long", long.class);
        primitiveClasses.put("byte", byte.class);
        primitiveClasses.put("char", char.class);
        primitiveClasses.put("float", float.class);
        primitiveClasses.put("double", double.class);
        primitiveClasses.put("boolean", boolean.class);
    }
    
    private ModuleContextImpl moduleContext;
    
    public Instanciator(ModuleContextImpl moduleContext) {
        this.moduleContext = moduleContext;
    }
    
    private void addBuiltinComponents(Repository repository) {
        if (moduleContext != null) {
            repository.add("moduleContext", moduleContext);
            repository.add("bundleContext", moduleContext.getBundleContext());                   
            repository.add("bundle", moduleContext.getBundleContext().getBundle());
            repository.add("conversionService", moduleContext.getConversionService());
        }
    }
    
    public Repository createRepository() throws Exception {
        ComponentDefinitionRegistryImpl registry = (ComponentDefinitionRegistryImpl)getComponentDefinitionRegistry();
        Repository repository = new ScopedRepository();
        addBuiltinComponents(repository);
        
        // Create type-converter recipes
        for (Value value : registry.getTypeConverters()) {
            if (value instanceof ComponentValue) {
                Recipe recipe = (Recipe) getValue(value, null);
                repository.add(recipe.getName(), recipe);
            } else if (value instanceof ReferenceValue) {
                ReferenceRecipe recipe = (ReferenceRecipe) getValue(value, null);
                repository.add(recipe.getReferenceName(), recipe);
            } else {
                throw new RuntimeException("Unexpected converter type: " + value);
            }
        }
        
        // Create component recipes
        for (String name : (Set<String>) registry.getComponentDefinitionNames()) {
            ComponentMetadata component = registry.getComponentDefinition(name);
            Recipe recipe = createRecipe(component);
            repository.add(name, recipe);
        }
        return repository;
    }

    private Recipe createRecipe(ComponentMetadata component) throws Exception {
        if (component instanceof LocalComponentMetadata) {
            return createComponentRecipe( (LocalComponentMetadata) component);
        } else if (component instanceof ServiceExportComponentMetadata) {
            return createServiceRecipe( (ServiceExportComponentMetadata) component);
        } else if (component instanceof UnaryServiceReferenceComponentMetadata) {
            UnaryServiceReferenceComponentMetadata metadata = (UnaryServiceReferenceComponentMetadata) component;
            CollectionRecipe cr = null;
            if (metadata.getBindingListeners() != null) {
                cr = new CollectionRecipe(ArrayList.class);;
                for (BindingListenerMetadata listener : (Collection<BindingListenerMetadata>) metadata.getBindingListeners()) {
                    cr.add(createRecipe(listener));
                }
            }
            ReferenceServiceRecipe recipe = new ReferenceServiceRecipe(moduleContext,
                                                                       moduleContext.getSender(),
                                                                       metadata,
                                                                       cr);
            recipe.setName(component.getName());
            return recipe;
        } else if (component instanceof CollectionBasedServiceReferenceComponentMetadata) {
            // TODO
            throw new IllegalStateException("Unsupported component type " + component.getClass());
        } else {
            throw new IllegalStateException("Unsupported component type " + component.getClass());
        }
    }

    private ObjectRecipe createServiceRecipe(ServiceExportComponentMetadata serviceExport) throws Exception {
        ObjectRecipe recipe = new ObjectRecipe(ServiceRegistrationProxy.class);
        recipe.allow(Option.PRIVATE_PROPERTIES);
        recipe.setName(serviceExport.getName());
        recipe.setProperty("moduleContext", moduleContext);
        LocalComponentMetadata exportedComponent = getLocalServiceComponent(serviceExport.getExportedComponent());
        if (exportedComponent != null && LocalComponentMetadata.SCOPE_BUNDLE.equals(exportedComponent.getScope())) {
            BlueprintObjectRecipe exportedComponentRecipe = createComponentRecipe(exportedComponent);
            recipe.setProperty("service", new BundleScopeServiceFactory(moduleContext, exportedComponentRecipe));
        } else {
            recipe.setProperty("service", getValue(serviceExport.getExportedComponent(), null));
        }
        recipe.setProperty("metadata", serviceExport);
        if (serviceExport instanceof ServiceExportComponentMetadataImpl) {
            ServiceExportComponentMetadataImpl impl = (ServiceExportComponentMetadataImpl) serviceExport;
            if (impl.getServicePropertiesValue() != null) {
                recipe.setProperty("serviceProperties", getValue(impl.getServicePropertiesValue(), null));
            }
        }
        if (serviceExport.getRegistrationListeners() != null) {
            CollectionRecipe cr = new CollectionRecipe(ArrayList.class);;
            for (RegistrationListenerMetadata listener : (Collection<RegistrationListenerMetadata>)serviceExport.getRegistrationListeners()) {
                cr.add(createRecipe(listener));
            }
            recipe.setProperty("listeners", cr);
        }
        return recipe;
    }

    private BlueprintObjectRecipe createComponentRecipe(LocalComponentMetadata local) throws Exception {
        BlueprintObjectRecipe recipe = new BlueprintObjectRecipe(loadClass(local.getClassName()));
        recipe.allow(Option.PRIVATE_PROPERTIES);
        recipe.setName(local.getName());
        for (PropertyInjectionMetadata property : (Collection<PropertyInjectionMetadata>) local.getPropertyInjectionMetadata()) {
            Object value = getValue(property.getValue(), null);
            recipe.setProperty(property.getName(), value);
        }
        if (LocalComponentMetadata.SCOPE_PROTOTYPE.equals(local.getScope())) {
            recipe.setKeepRecipe(true);
        }
        ComponentDefinitionRegistryImpl registry = (ComponentDefinitionRegistryImpl)getComponentDefinitionRegistry();
        // check for init-method and set it on Recipe
        String initMethod = local.getInitMethodName();
        if (initMethod == null) {
            Method method = ReflectionUtils.getLifecycleMethod(recipe.getType(), registry.getDefaultInitMethod());
            recipe.setInitMethod(method);
        } else if (initMethod.length() > 0) {
            Method method = ReflectionUtils.getLifecycleMethod(recipe.getType(), initMethod);
            if (method == null) {
                throw new ConstructionException("Component '" + local.getName() + "' does not have init-method: " + initMethod);
            }
            recipe.setInitMethod(method);
        }
        // check for destroy-method and set it on Recipe
        String destroyMethod = local.getDestroyMethodName();
        if (destroyMethod == null) {
            Method method = ReflectionUtils.getLifecycleMethod(recipe.getType(), registry.getDefaultDestroyMethod());
            recipe.setDestroyMethod(method);
        } else if (destroyMethod.length() > 0) {
            Method method = ReflectionUtils.getLifecycleMethod(recipe.getType(), destroyMethod);
            if (method == null) {
                throw new ConstructionException("Component '" + local.getName() + "' does not have destroy-method: " + destroyMethod);
            }
            recipe.setDestroyMethod(method);
        }
        // TODO: constructor args
        // TODO: factory-method
        // TODO: factory-component
        return recipe;
    }

    private Recipe createRecipe(RegistrationListenerMetadata listener) throws Exception {
        ObjectRecipe recipe = new ObjectRecipe(ServiceRegistrationProxy.Listener.class);
        recipe.allow(Option.PRIVATE_PROPERTIES);
        recipe.setProperty("listener", getValue(listener.getListenerComponent(), null));
        recipe.setProperty("metadata", listener);
        return recipe;
    }

    private Recipe createRecipe(BindingListenerMetadata listener) throws Exception {
        ObjectRecipe recipe = new ObjectRecipe(ReferenceServiceRecipe.Listener.class);
        recipe.allow(Option.PRIVATE_PROPERTIES);
        recipe.setProperty("listener", getValue(listener.getListenerComponent(), null));
        recipe.setProperty("metadata", listener);
        return recipe;
    }

    private LocalComponentMetadata getLocalServiceComponent(Value value) throws Exception {
        ComponentMetadata metadata = null;
        if (value instanceof ReferenceValue) {
            ReferenceValue ref = (ReferenceValue) value;
            ComponentDefinitionRegistry registry = getComponentDefinitionRegistry();
            metadata = registry.getComponentDefinition(ref.getComponentName());
        } else if (value instanceof ComponentValue) {
            ComponentValue comp = (ComponentValue) value;
            metadata = comp.getComponentMetadata();
        }
        if (metadata instanceof LocalComponentMetadata) {
            return (LocalComponentMetadata) metadata;
        } else {
            return null;
        }
    }
    
    private Object getValue(Value v, Class groupingType) throws Exception {
        if (v instanceof NullValue) {
            return null;
        } else if (v instanceof TypedStringValue) {
            TypedStringValue stringValue = (TypedStringValue) v; 
            String value = stringValue.getStringValue();
            Class type = loadClass(stringValue.getTypeName());
            return new ValueRecipe(getConversionService(), value, type, groupingType);
        } else if (v instanceof ReferenceValue) {
            String componentName = ((ReferenceValue) v).getComponentName();
            return new ReferenceRecipe(componentName);
        } else if (v instanceof ListValue) {
            ListValue listValue = (ListValue) v;
            Class type = loadClass(listValue.getValueType());
            CollectionRecipe cr = new CollectionRecipe(ArrayList.class);
            for (Value lv : (List<Value>) listValue.getList()) {
                cr.add(getValue(lv, type));
            }
            return cr;
        } else if (v instanceof SetValue) {
            SetValue setValue = (SetValue) v;
            Class type = loadClass(setValue.getValueType());
            CollectionRecipe cr = new CollectionRecipe(HashSet.class);
            for (Value lv : (Set<Value>) setValue.getSet()) {
                cr.add(getValue(lv, type));
            }
            return cr;
        } else if (v instanceof MapValue) {
            MapValue mapValue = (MapValue) v;
            Class keyType = loadClass(mapValue.getKeyType());
            Class valueType = loadClass(mapValue.getValueType());            
            MapRecipe mr = new MapRecipe(HashMap.class);
            for (Map.Entry<Value,Value> entry : ((Map<Value,Value>) mapValue.getMap()).entrySet()) {
                Object key = getValue(entry.getKey(), keyType);
                Object val = getValue(entry.getValue(), valueType);
                mr.put(key, val);
            }
            return mr;
        } else if (v instanceof ArrayValue) {
            ArrayValue arrayValue = (ArrayValue) v;
            Class type = loadClass(arrayValue.getValueType());
            ArrayRecipe ar = (type == null) ? new ArrayRecipe() : new ArrayRecipe(type);
            for (Value value : arrayValue.getArray()) {
                ar.add(getValue(value, type));
            }
            return ar;
        } else if (v instanceof ComponentValue) {
            return createRecipe(((ComponentValue) v).getComponentMetadata());
        } else if (v instanceof PropertiesValue) {
            return ((PropertiesValue) v).getPropertiesValue();
        } else if (v instanceof ReferenceNameValue) {
            return ((ReferenceNameValue) v).getReferenceName();
        } else {
            throw new IllegalStateException("Unsupported value: " + v.getClass().getName());
        }
    }
    
    protected ComponentDefinitionRegistry getComponentDefinitionRegistry() {
        return moduleContext.getComponentDefinitionRegistry();
    }
    
    protected ConversionService getConversionService() {
        return moduleContext.getConversionService();
    }
    
    private Class loadClass(String typeName) throws ClassNotFoundException {
        if (typeName == null) {
            return null;
        }

        Class clazz = primitiveClasses.get(typeName);
        if (clazz == null) {
            if (moduleContext == null) {
                ClassLoader loader = Thread.currentThread().getContextClassLoader();
                clazz = loader.loadClass(typeName);
            } else {
                clazz = moduleContext.getBundleContext().getBundle().loadClass(typeName);
            }
        }
        return clazz;
    }
                
}
