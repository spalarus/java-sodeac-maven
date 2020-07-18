/*******************************************************************************
 * Copyright (c) 2020 Sebastian Palarus
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v2.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v20.html
 *
 * Contributors:
 *     Sebastian Palarus - initial API and implementation
 *******************************************************************************/
package org.sodeac.maven;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.Map.Entry;
import java.util.Objects;

import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamWriter;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;

import spoon.MavenLauncher;
import spoon.reflect.CtModel;
import spoon.reflect.code.CtExpression;
import spoon.reflect.code.CtNewArray;
import spoon.reflect.declaration.CtAnnotation;
import spoon.reflect.declaration.CtClass;
import spoon.reflect.declaration.CtElement;
import spoon.reflect.declaration.CtType;
import spoon.reflect.reference.CtFieldReference;
import spoon.reflect.reference.CtPackageReference;
import spoon.reflect.reference.CtTypeReference;

@Mojo(name = "generate-service-descriptor", defaultPhase = LifecyclePhase.GENERATE_RESOURCES)
public class ServiceDescriptorMojo extends AbstractMojo
{
	// mvn org.sodeac:org.sodeac.mvn.plugin:generate-service-descriptor
	
	public static final String DEFAULT_LOCAL_FACTORY = "org.sodeac.common.impl.NodeConfigurationImpl$LocalServiceFactory";
	public static final String NO_REQUIRED_CONFIGURATION = "org.sodeac.common.annotation.ServiceFactory$NoRequiredConfiguration";
	public static final String DEFAULT_REGISTRATION_NAME = "<REPLACED__BY__CLASS__NAME>";
	public static final String DEFAULT_REGISTRATION_DOMAIN = "<REPLACED__BY__PACKAGE__NAME>";
	
	@Parameter(property = "srv-descriptor-dir", defaultValue = "${basedir}/src/main/resources/SDC-INF")
	private String outputdir;
 
	@Parameter(property = "project", readonly = true)
	private MavenProject project;

	public void execute() throws MojoExecutionException, MojoFailureException
	{
		try
		{
			File outputDirFile = new File(outputdir);
			if(! outputDirFile.exists())
			{
				outputDirFile.mkdirs();
			}
			else
			{
				for(File file :outputDirFile.listFiles())
				{
					file.delete();
				}
			}
			
			MavenLauncher launcher = new MavenLauncher(project.getBasedir().getCanonicalPath(), MavenLauncher.SOURCE_TYPE.APP_SOURCE);
			launcher.buildModel();
			CtModel model = launcher.getModel();
			
			for(CtType<?> s : model.getAllTypes()) 
			{
				if(! (s instanceof CtClass))
				{
					continue;
				}
				CtClass javaClass = (CtClass)s;
				
				boolean hasServiceFactory = false;
				for(CtAnnotation<? extends Annotation> annotation : javaClass.getAnnotations())
				{
					if("org.sodeac.common.annotation.ServiceFactory".equals(annotation.getAnnotationType().getQualifiedName()))
					{
						hasServiceFactory = true;
						break;
					}
				}
				if(! hasServiceFactory)
				{
					continue;
				}
				
				Map<String,Object> defaultProperties = new HashMap<String, Object>();
				
				parseProperties(javaClass.getAnnotations(), defaultProperties);
				String defaultVersion = parseVersionFromList(javaClass.getAnnotations());
				
				String namespace = "http://www.sodeac.org/xmlns/serviceregistration/v1.0.0";
				FileOutputStream os = null;
				try
				{
					String className = javaClass.getQualifiedName();
					File outFile = new File(outputDirFile,className + ".xml");
					os = new FileOutputStream(outFile);
					XMLStreamWriter out = XMLOutputFactory.newInstance().createXMLStreamWriter( new OutputStreamWriter(os, "UTF-8"));
					try
					{
						out.setDefaultNamespace(namespace);
						out.writeStartDocument("UTF-8", "1.0");
						
						out.writeCharacters("\n");
						out.writeStartElement(namespace,"servicecomponent");
						out.writeDefaultNamespace(namespace);
						out.writeAttribute("type", "org.sodeac.common");
						out.writeCharacters("\n");
						
						out.writeCharacters("  ");out.writeStartElement(namespace, "factories");
						out.writeCharacters("\n");
						
						List<ServiceRegistration> serviceRegistrationList = new ArrayList<>();
						parseServiceRegistration(javaClass.getAnnotations(), serviceRegistrationList);
						
						for(CtAnnotation<? extends Annotation> annotation : javaClass.getAnnotations())
						{
							if("org.sodeac.common.annotation.ServiceFactory".equals(annotation.getAnnotationType().getQualifiedName()))
							{
								int lowerScalingLimit = 1;
								int upperScalingLimit = 1;
								int initialScaling = 0;
								boolean shared = true;
								String requiredConfigurationClass = "";
								String factoryClass = DEFAULT_LOCAL_FACTORY;
								
								List<ServiceRegistration> currentServiceRegistrationList = new ArrayList<>(serviceRegistrationList);
								
								Map<String,Object> properties = new HashMap<>(defaultProperties);
								
								for(Entry<String,CtExpression> valEntry : annotation.getValues().entrySet())
								{
									if(valEntry.getKey().endsWith("Property"))
									{
										if(valEntry.getValue() instanceof CtNewArray)
										{
											parseProperties(((CtNewArray)valEntry.getValue()).getElements(), properties);
										}
										else
										{
											parseProperties((List)Arrays.asList(new CtExpression[] {valEntry.getValue()}), properties);	
										}
									}
									else if("registrations".equals(valEntry.getKey()))
									{
										if(valEntry.getValue() instanceof CtNewArray)
										{
											parseServiceRegistration(((CtNewArray)valEntry.getValue()).getElements(), currentServiceRegistrationList);
										}
										else
										{
											parseServiceRegistration((List)Arrays.asList(new CtExpression[] {valEntry.getValue()}), currentServiceRegistrationList);	
										}
									}
									else if("lowerScalingLimit".equals(valEntry.getKey()))
									{
										try
										{
											lowerScalingLimit = Integer.parseInt(valEntry.getValue().toString().trim());
										}
										catch (Exception e) {}
									}
									else if("upperScalingLimit".equals(valEntry.getKey()))
									{
										try
										{
											upperScalingLimit = Integer.parseInt(valEntry.getValue().toString().trim());
										}
										catch (Exception e) {}
									}
									else if("initialScaling".equals(valEntry.getKey()))
									{
										try
										{
											initialScaling = Integer.parseInt(valEntry.getValue().toString().trim());
										}
										catch (Exception e) {}
									}
									else if("shared".equals(valEntry.getKey()))
									{
										try
										{
											shared = Boolean.parseBoolean(valEntry.getValue().toString().trim());
										}
										catch (Exception e) {}
									}
									else if("requiredConfigurationClass".equals(valEntry.getKey()))
									{
										try
										{
											requiredConfigurationClass = expressionToClassNameDefinition(valEntry.getValue()).toString();
											if(NO_REQUIRED_CONFIGURATION.equals(requiredConfigurationClass))
											{
												requiredConfigurationClass = "";
											}
										}
										catch (Exception e) {}
									}
									else if("factoryClass".equals(valEntry.getKey()))
									{
										try
										{
											factoryClass = expressionToClassNameDefinition(valEntry.getValue()).toString();
										}
										catch (Exception e) {}
									}
									
								}
								
								out.writeCharacters("    ");out.writeStartElement(namespace, "factory");
								out.writeAttribute("lower-scaling-limit", Integer.toString(lowerScalingLimit));
								out.writeAttribute("upper-scaling-limit", Integer.toString(upperScalingLimit));
								out.writeAttribute("initial-scaling", Integer.toString(initialScaling));
								out.writeAttribute("shared", Boolean.toString(shared));
								if(! requiredConfigurationClass.isEmpty())
								{
									out.writeAttribute("require-configuration-class", requiredConfigurationClass);
								}
								out.writeAttribute("class",factoryClass);
								out.writeCharacters("\n");
								
								
								for(Entry<String,Object> propertyEntry : properties.entrySet())
								{
									out.writeCharacters("      ");out.writeStartElement(namespace, "property");
									
									String type = "string";
									
									if(propertyEntry.getValue() instanceof Boolean)
									{
										type = "boolean";
									}
									else if(propertyEntry.getValue() instanceof Long)
									{
										type = "int";
									}
									else if(propertyEntry.getValue() instanceof Double)
									{
										type = "dec";
									}
									
									out.writeAttribute("key", propertyEntry.getKey());
									out.writeAttribute("type", type);
									out.writeCharacters(propertyEntry.getValue().toString());
									
									out.writeEndElement(); // property
									out.writeCharacters("\n");
								}
								
								for(ServiceRegistration serviceRegistration : currentServiceRegistrationList)
								{
									List<ClassNameDefinition> definitionList = new ArrayList<>(serviceRegistration.getServiceTypes());
									if(definitionList.isEmpty())
									{
										definitionList.add(new ClassNameDefinition(javaClass.getPackage().getQualifiedName(), javaClass.getQualifiedName().substring(javaClass.getPackage().getQualifiedName().length() + 1)));
									}
									
									String domain = serviceRegistration.getDomain();
									String name = serviceRegistration.getName();
									String version = serviceRegistration.getVersion();
									
									if(version == null)
									{
										version = defaultVersion;
									}
									
									if(version == null)
									{
										version = "1.0.0";
									}
									
									for(ClassNameDefinition classNameDefinition : definitionList)
									{
										out.writeCharacters("      ");out.writeStartElement(namespace, "registration");
										
										out.writeAttribute("domain", DEFAULT_REGISTRATION_DOMAIN.equals(domain) ? classNameDefinition.getPackageName() : domain);
										out.writeAttribute("name", DEFAULT_REGISTRATION_NAME.equals(name) ? classNameDefinition.getClassName() : name);
										out.writeAttribute("version", version);
										out.writeAttribute("interface", classNameDefinition.toString());
										out.writeEndElement(); // registration
										out.writeCharacters("\n");
									}
									
								}
								
								out.writeCharacters("    ");out.writeEndElement(); // factory
								out.writeCharacters("\n");
							}
						}
						
						out.writeCharacters("  ");out.writeEndElement(); // factories
						out.writeCharacters("\n");
						
						boolean referenceElementCreated = false;
						
						for(CtFieldReference field : javaClass.getAllFields())
						{
							if(! "org.sodeac.common.IService$IServiceProvider".equals(field.getType().getQualifiedName()))
							{
								continue;
							}
							
							if(! referenceElementCreated)
							{
								referenceElementCreated = true;
								
								out.writeCharacters("  ");out.writeStartElement(namespace, "references");
								out.writeCharacters("\n");
							}
							
							
							
							CtTypeReference reference = (CtTypeReference)field.getType();
							String typeQualifiedName = null;
							String typePackage = null;
							String typeName = null;
							
							if(reference.getActualTypeArguments().size() == 1)
							{
								CtTypeReference ref = reference.getActualTypeArguments().get(0);
								
								typeQualifiedName = ref.getQualifiedName();
								for(CtElement element : ref.asIterable())
								{
									if(element instanceof CtTypeReference)
									{
										typeName = ((CtTypeReference)element).getSimpleName(); // TODO  qualified - package ??? QualifiedName().substring(packageName.length() + 1 );
									}
									if(element instanceof CtPackageReference)
									{
										typePackage = ((CtPackageReference)element).getQualifiedName();
									}
								}
							}
							
							String addressDomain = "";
							String addressName = "";
							String addressMinVersion = null;
							String addressBeforeVersion = null;
							String addressFilter = null;
							
							boolean preferences = false;
							
							for(CtAnnotation<? extends Annotation> annotation : field.getDeclaration().getAnnotations())
							{
								if("org.sodeac.common.annotation.ServicePreference".equals(annotation.getAnnotationType().getQualifiedName()))
								{
									preferences = true;
								}
							}
							
							for(CtAnnotation<? extends Annotation> annotation : field.getDeclaration().getAnnotations())
							{
								if("org.sodeac.common.annotation.ServiceAddress".equals(annotation.getAnnotationType().getQualifiedName()))
								{
									for(Entry<String,CtExpression> valEntry : annotation.getValues().entrySet())
									{
										if("name".equals(valEntry.getKey()))
										{
											addressName = parseAnnotationString(valEntry.getValue().toString());
										}
										else if("domain".equals(valEntry.getKey()))
										{
											addressDomain = parseAnnotationString(valEntry.getValue().toString());
										}
										else if("filter".equals(valEntry.getKey()))
										{
											addressFilter = parseAnnotationString(valEntry.getValue().toString());
										}
										else if("minVersion".equals(valEntry.getKey()))
										{
											addressMinVersion = parseVersion((CtAnnotation)valEntry.getValue());
										}
										else if("beforeVersion".equals(valEntry.getKey()))
										{
											addressBeforeVersion = parseVersion((CtAnnotation)valEntry.getValue());
										}
									}
								}
							}
							
							if(((addressName == null) || addressName.isEmpty()) && (typeName != null) && (! typeName.isEmpty()))
							{
								addressName = typeName;
							}
							
							if(((addressDomain == null) || addressDomain.isEmpty()) && (typePackage != null) && (! typePackage.isEmpty()))
							{
								addressDomain = typePackage;
							}
							
							out.writeCharacters("    ");out.writeStartElement(namespace, "reference");
							
							out.writeAttribute("name", field.getSimpleName()); // TODO qualified - Package ???? - QualifiedName().substring(packageName.length() + 1 );
							out.writeAttribute("service-type", typeQualifiedName);
							
							out.writeAttribute("service-name", addressName);
							out.writeAttribute("service-domain", addressDomain);
							
							if(addressMinVersion != null)
							{
								out.writeAttribute("service-min-version", addressMinVersion);
							}
							if(addressBeforeVersion != null)
							{
								out.writeAttribute("service-before-version", addressBeforeVersion);
							}
							if((addressFilter != null) && (! addressFilter.isEmpty()))
							{
								out.writeAttribute("service-filter", addressFilter);
							}
							
							if(preferences)
							{
								out.writeCharacters("\n");
								
								for(CtAnnotation<? extends Annotation> annotation : field.getDeclaration().getAnnotations())
								{
									if("org.sodeac.common.annotation.ServicePreference".equals(annotation.getAnnotationType().getQualifiedName()))
									{
										out.writeCharacters("      ");out.writeStartElement(namespace, "preference");
										
										for(Entry<String,CtExpression> valEntry : annotation.getValues().entrySet())
										{
											if("score".equals(valEntry.getKey()))
											{
												out.writeAttribute("score",valEntry.getValue().toString());
											}
										}
										
										for(Entry<String,CtExpression> valEntry : annotation.getValues().entrySet())
										{
											if("filter".equals(valEntry.getKey()))
											{
												out.writeCharacters(parseAnnotationString(valEntry.getValue().toString()));
											}
										}
										
										out.writeEndElement(); // preference
										out.writeCharacters("\n");
									}
								}
								
								out.writeCharacters("    ");out.writeEndElement(); // reference
								out.writeCharacters("\n");
							}
							else
							{
								out.writeEndElement(); // reference
								out.writeCharacters("\n");
							}
							
						}
						
						if(referenceElementCreated)
						{
							out.writeCharacters("  ");out.writeEndElement(); // references
							out.writeCharacters("\n");
						}
						
						out.writeEndElement(); // servicecomponent
						out.writeEndDocument();
					}
					finally 
					{
						out.close();
					}
				}
				catch (Exception e) 
				{
					if(e instanceof RuntimeException)
					{
						throw e;
					}
					throw new RuntimeException(e);
				}
				finally 
				{
					try
					{
						os.close();
					}
					catch (Exception e) {}
				}
			}
		}
		catch (Exception e) 
		{
			throw new MojoExecutionException("error generate service descriptor ",e); 
		}

	}
	
	private String parseVersionFromList(List<? extends CtExpression<?>> list)
	{
		for(CtExpression expression : list)
		{
			if(! (expression instanceof CtAnnotation))
			{
				continue;
			}
			
			CtAnnotation<? extends Annotation> annotation = (CtAnnotation)expression;
			
			if("org.sodeac.common.annotation.Version".equals(annotation.getAnnotationType().getQualifiedName()))
			{
				return parseVersion(annotation);
			}
		}
		
		return null;
	}
	
	private String parseVersion(CtAnnotation<? extends Annotation> annotation)
	{
		if("org.sodeac.common.annotation.Version".equals(annotation.getAnnotationType().getQualifiedName()))
		{
			int major = 1;
			int minor = 0;
			int service = 0;
			
			for(Entry<String,CtExpression> valEntry : annotation.getValues().entrySet())
			{
				try
				{
					if("major".equals(valEntry.getKey()))
					{
						major = Integer.parseInt(valEntry.getValue().toString().trim());
					}
					else if("minor".equals(valEntry.getKey()))
					{
						minor = Integer.parseInt(valEntry.getValue().toString().trim());
					}
					else if("service".equals(valEntry.getKey()))
					{
						service = Integer.parseInt(valEntry.getValue().toString().trim());
					}
				}
				catch (Exception e) {}
			}
			
			if((major == -1) && (minor == -1) && (service == -1))
			{
				return null;
			}
			
			return major + "." + minor + "." + service;
		}
		return null;
	}
	
	private void parseServiceRegistration(List<? extends CtExpression<?>> list, List<ServiceRegistration> serviceRegistrationList)
	{
		for(CtExpression expression : list)
		{
			if(! (expression instanceof CtAnnotation))
			{
				continue;
			}
			
			CtAnnotation<? extends Annotation> annotation = (CtAnnotation)expression;
			
			if("org.sodeac.common.annotation.ServiceRegistration".equals(annotation.getAnnotationType().getQualifiedName()))
			{
				
				String version = null;
				String name = DEFAULT_REGISTRATION_NAME;
				String domain = DEFAULT_REGISTRATION_DOMAIN;
				List<ClassNameDefinition> serviceTypes = new ArrayList<>();
				
				for(Entry<String,CtExpression> valEntry : annotation.getValues().entrySet())
				{
					if("version".equals(valEntry.getKey()))
					{
						version = parseVersion((CtAnnotation)valEntry.getValue());
					}
					else if("name".equals(valEntry.getKey()))
					{
						name = parseAnnotationString(valEntry.getValue().toString());
					}
					else if("domain".equals(valEntry.getKey()))
					{
						domain = parseAnnotationString(valEntry.getValue().toString());
					}
					else if("serviceType".equals(valEntry.getKey()))
					{
						if(valEntry.getValue() instanceof CtNewArray)
						{
							for(CtExpression<?> childExpression : (List<CtExpression<?>>)((CtNewArray)valEntry.getValue()).getElements())
							{
								serviceTypes.add(expressionToClassNameDefinition(childExpression));
							}
						}
						else
						{
							try
							{
								serviceTypes.add(expressionToClassNameDefinition(valEntry.getValue()));
							}
							catch (Exception e) {}
						}
					}
				}
				
				serviceRegistrationList.add(new ServiceRegistration(name, domain, version, serviceTypes));
			}
		}
	}
	
	private void parseProperties(List<? extends CtExpression<?>> list, Map<String,Object> properties)
	{
		for(CtExpression expression : list)
		{
			if(! (expression instanceof CtAnnotation))
			{
				continue;
			}
			
			CtAnnotation<? extends Annotation> annotation = (CtAnnotation)expression;
			
			if("org.sodeac.common.annotation.StringProperty".equals(annotation.getAnnotationType().getQualifiedName()))
			{
				String key = UUID.randomUUID().toString();
				String value = UUID.randomUUID().toString();
				
				for(Entry<String,CtExpression> valEntry : annotation.getValues().entrySet())
				{
					try
					{
						if("key".equals(valEntry.getKey()))
						{
							key = parseAnnotationString(valEntry.getValue().toString());
						}
						else if("value".equals(valEntry.getKey()))
						{
							value = parseAnnotationString(valEntry.getValue().toString());
						}
					}
					catch (Exception e) {}
				}
				properties.put(key, value);
			}
			else if("org.sodeac.common.annotation.BooleanProperty".equals(annotation.getAnnotationType().getQualifiedName()))
			{
				String key = UUID.randomUUID().toString();
				Boolean value = false;
				
				for(Entry<String,CtExpression> valEntry : annotation.getValues().entrySet())
				{
					try
					{
						if("key".equals(valEntry.getKey()))
						{
							key = parseAnnotationString(valEntry.getValue().toString());
						}
						else if("value".equals(valEntry.getKey()))
						{
							value = Boolean.parseBoolean(valEntry.getValue().toString().trim());
						}
					}
					catch (Exception e) {}
				}
				properties.put(key, value);
			}
			else if("org.sodeac.common.annotation.IntegerProperty".equals(annotation.getAnnotationType().getQualifiedName()))
			{
				String key = UUID.randomUUID().toString();
				Long value = 0L;
				
				for(Entry<String,CtExpression> valEntry : annotation.getValues().entrySet())
				{
					try
					{
						if("key".equals(valEntry.getKey()))
						{
							key = parseAnnotationString(valEntry.getValue().toString());
						}
						else if("value".equals(valEntry.getKey()))
						{
							value = Long.parseLong(valEntry.getValue().toString().trim());
						}
					}
					catch (Exception e) {}
				}
				properties.put(key, value);
			}
			else if("org.sodeac.common.annotation.DecimalProperty".equals(annotation.getAnnotationType().getQualifiedName()))
			{
				String key = UUID.randomUUID().toString();
				Double value = 0.0d;
				
				for(Entry<String,CtExpression> valEntry : annotation.getValues().entrySet())
				{
					try
					{
						if("key".equals(valEntry.getKey()))
						{
							key = parseAnnotationString(valEntry.getValue().toString());
						}
						else if("value".equals(valEntry.getKey()))
						{
							value = Double.parseDouble(valEntry.getValue().toString().trim());
						}
					}
					catch (Exception e) {}
				}
				properties.put(key, value);
			}
		}
	}
	
	private String parseAnnotationString(String value)
	{
		if(value == null)
		{
			return value;
		}
		if(value.isEmpty())
		{
			return value;
		}
		value = value.trim();
		if((value.length() >= 2) && value.startsWith("\"") && value.endsWith("\""))
		{
			return value.substring(1, value.length() -1);
		}
		return value;
	}
	
	private ClassNameDefinition expressionToClassNameDefinition(CtExpression<?> expression)
	{
		String packageName = null;
		String className = null;
		for(CtElement el : expression.asIterable())
		{
			if(el instanceof CtPackageReference)
			{
				if(packageName == null)
				{
					packageName = ((CtPackageReference)el).getQualifiedName();
				}
			}
		}
		
		Objects.requireNonNull(packageName,"package name not found");
		if(packageName.isEmpty())
		{
			throw new IllegalStateException("package name not found");
		}
		
		for(CtElement el : expression.asIterable())
		{
			if(el instanceof CtTypeReference)
			{
				if(className == null)
				{
					className = ((CtTypeReference)el).getQualifiedName().substring(packageName.length() + 1 );
				}
			}
		}
		Objects.requireNonNull(className,"class name not found");
		if(className.isEmpty())
		{
			throw new IllegalStateException("class name not found");
		}
		
		return new ClassNameDefinition(packageName, className);
	}
	
	private class ClassNameDefinition
	{
		public ClassNameDefinition(String packageName, String className)
		{
			super();
			this.packageName = packageName;
			this.className = className;
		}
		
		private String packageName = null;
		private String className = null;
		
		public String getPackageName()
		{
			return packageName;
		}
		public String getClassName()
		{
			return className;
		}
		@Override
		public String toString()
		{
			return this.packageName + "." + className;
		}
		
		
	}
	
	private class ServiceRegistration
	{
		private ServiceRegistration(String name, String domain, String version, List<ClassNameDefinition> serviceTypes)
		{
			super();
			this.name = name;
			this.domain = domain;
			this.version = version;
			this.serviceTypes = serviceTypes;
		}
		
		String name = null;
		String domain = null;
		String version = null;
		List<ClassNameDefinition> serviceTypes = null;
		
		public String getName()
		{
			return name;
		}
		public void setName(String name)
		{
			this.name = name;
		}
		public String getDomain()
		{
			return domain;
		}
		public void setDomain(String domain)
		{
			this.domain = domain;
		}
		public String getVersion()
		{
			return version;
		}
		public void setVersion(String version)
		{
			this.version = version;
		}
		public List<ClassNameDefinition> getServiceTypes()
		{
			return serviceTypes;
		}
		public void setServiceTypes(List<ClassNameDefinition> serviceTypes)
		{
			this.serviceTypes = serviceTypes;
		}
	}

}
