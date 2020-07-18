package org.sodeac.maven;


import java.io.File;
import java.io.FileOutputStream;
import java.lang.annotation.Annotation;
import java.util.UUID;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map.Entry;
import java.util.Set;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;

import spoon.MavenLauncher;
import spoon.reflect.CtModel;
import spoon.reflect.code.CtExpression;
import spoon.reflect.declaration.CtAnnotation;
import spoon.reflect.declaration.CtClass;
import spoon.reflect.declaration.CtElement;
import spoon.reflect.declaration.CtType;
import spoon.reflect.reference.CtFieldReference;
import spoon.reflect.reference.CtPackageReference;
import spoon.reflect.reference.CtTypeReference;

@Mojo(name = "generate-branchnode-to-object-wrapper", defaultPhase = LifecyclePhase.GENERATE_SOURCES)
public class GenerateBranchNodeToObjectWrapperMojo extends AbstractMojo
{
	@Parameter(property = "sourcedir", defaultValue = "")
	private String sourcedir;
	
	@Parameter(property = "outputdir", defaultValue = "${basedir}/src/generated")
	private String outputdir;
 
	@Parameter(property = "project", readonly = true)
	private MavenProject project;
	

	public void execute() throws MojoExecutionException, MojoFailureException
	{
		File sourceRoot = new File(outputdir);
		
		try
		{
			MavenLauncher launcher = new MavenLauncher(project.getBasedir().getCanonicalPath(), MavenLauncher.SOURCE_TYPE.APP_SOURCE);
			launcher.buildModel();
			CtModel model = launcher.getModel();
			
			// Generate BowFactories
			
			for(CtType<?> s : model.getAllTypes()) 
			{
				if(! (s instanceof CtClass))
				{
					continue;
				}
				CtClass javaClass = (CtClass)s;
				
				boolean hasToGenerateObjectWrapper = false;
				
				for(CtAnnotation<? extends Annotation> annotation : javaClass.getAnnotations())
				{
					if("org.sodeac.common.annotation.GenerateBowFactory".equals(annotation.getAnnotationType().getQualifiedName()))
					{
						hasToGenerateObjectWrapper = true;
						
						break;
					}
				}
				if(! hasToGenerateObjectWrapper)
				{
					continue;
				}
				
				CtClass superClass = (CtClass)javaClass.getSuperclass().getTypeDeclaration();
				String generatedPackageName = getBOWFactoryPackage(javaClass);
				String generatedClassName = getBOWFactoryName(javaClass);
				
				// TODO license header from POM
				
				StringBuilder classBuilder = new StringBuilder();
				
				classBuilder.append("package " + generatedPackageName + ";\n");
				classBuilder.append("\n");
				if("org.sodeac.common.typedtree.TypedTreeMetaModel".equals(superClass.getQualifiedName()))
				{
					classBuilder.append("public class " + generatedClassName + "\n");
				}
				else
				{
					classBuilder.append("public class " + generatedClassName + " extends " + getBOWFactoryPackage(superClass) + "." + getBOWFactoryName(superClass) + "\n");
				}
				
				classBuilder.append("{\n");
				classBuilder.append("\t\n");
				
				for(CtFieldReference field : javaClass.getAllFields())
				{
					if(!"org.sodeac.common.typedtree.BranchNodeType".equals(field.getType().getQualifiedName()))
					{
						continue;
					}
					
					CtTypeReference reference = (CtTypeReference)field.getType();
					if(reference.getActualTypeArguments().size() != 2)
					{
						continue;
					}
					
					if(! reference.getActualTypeArguments().get(0).getQualifiedName().equals(javaClass.getQualifiedName()))
					{
						continue;
					}
					
					CtTypeReference ref = reference.getActualTypeArguments().get(1);
					
					String typeQualifiedName = ref.getQualifiedName();
					String typeName = null;
					String typePackage = null;
					for(CtElement element : ref.asIterable())
					{
						if(element instanceof CtTypeReference)
						{
							typeName = ((CtTypeReference)element).getSimpleName();
						}
						if(element instanceof CtPackageReference)
						{
							typePackage = ((CtPackageReference)element).getQualifiedName();
						}
					}
					
					classBuilder.append("\tpublic static " + typePackage + "." + getBOWName(typeName) + "<?> create" + 
					  ( field.getSimpleName().length() == 1 ? 
							field.getSimpleName().toUpperCase() : 
							field.getSimpleName().substring(0, 1).toUpperCase()  + field.getSimpleName().substring(1)
					  ) + "()\n");
					
					classBuilder.append("\t{\n");
					classBuilder.append("\t\treturn new "  + typePackage + "." + getBOWName(typeName) + "(org.sodeac.common.typedtree.ModelRegistry.getTypedTreeMetaModel(" + javaClass.getQualifiedName() + ".class).createRootNode(" + javaClass.getQualifiedName() + "." + field.getSimpleName() + "),null);\n");
					classBuilder.append("\t}\n");
					classBuilder.append("\t\n");
				}
				
				classBuilder.append("}\n");
				
				System.out.println("Generate BOW Factory " + generatedPackageName  + "." + generatedClassName);
				
				File outputDirFile = new File(sourceRoot,generatedPackageName.replace('.', '/'));
				if(!outputDirFile.exists())
				{
					outputDirFile.mkdirs();
				}
				File outputFile = new File(outputDirFile,generatedClassName +".java");
				FileOutputStream fos = new FileOutputStream(outputFile);
				try
				{
					fos.write(classBuilder.toString().getBytes());
				}
				finally 
				{
					fos.close();
				}
			}
			
			// Generate Bows
			
			Set<CtClass> toGenerateSet = new HashSet<>();
			
			for(CtType<?> s : model.getAllTypes()) 
			{
				if(! (s instanceof CtClass))
				{
					continue;
				}
				CtClass javaClass = (CtClass)s;
				
				boolean hasToGenerateObjectWrapper = false;
				
				for(CtAnnotation<? extends Annotation> annotation : javaClass.getAnnotations())
				{
					if("org.sodeac.common.annotation.GenerateBow".equals(annotation.getAnnotationType().getQualifiedName()))
					{
						hasToGenerateObjectWrapper = true;
						
						break;
					}
				}
				if(! hasToGenerateObjectWrapper)
				{
					continue;
				}
				
				toGenerateSet.add(javaClass);
			}
			
			boolean checkMore = true;
			while(checkMore)
			{
				checkMore = false;
				for(CtClass javaClass : toGenerateSet)
				{
					if(javaClass.getSuperclass().getTypeDeclaration() instanceof  CtClass)
					{
						CtClass superClass = (CtClass)javaClass.getSuperclass().getTypeDeclaration();
						if("org.sodeac.common.typedtree.BranchNodeMetaModel".equals(superClass.getQualifiedName()))
						{
							continue;
						}
						if("org.sodeac.common.typedtree.TypedTreeMetaModel".equals(superClass.getQualifiedName()))
						{
							continue;
						}
						
						if(toGenerateSet.contains(superClass))
						{
							continue;
						}
						checkMore = true;
						toGenerateSet.add(superClass);
					}
				}
			}
			
			for(CtClass javaClass : toGenerateSet)
			{
				CtClass superClass = (CtClass)javaClass.getSuperclass().getTypeDeclaration();
				
				String className = javaClass.getQualifiedName().substring(javaClass.getPackage().getQualifiedName().length() + 1);
				String packageName = javaClass.getPackage().getQualifiedName();
				
				String generatedPackageName = getBOWPackage(javaClass);
				String generatedClassName = getBOWName(javaClass);
				
				String typeName = generatedClassName.substring(0, generatedClassName.length() -3);
				
				String factoriesFieldName = "FIELD_FACORIES_" + generatedPackageName.replace('.', '_') + "__" + generatedClassName;
				String instanceFactoryFieldName = "fieldFactory_" + generatedPackageName.replace('.', '_') + "__" + generatedClassName;
				
				boolean beanLikeSetters = false;
				for(CtAnnotation<? extends Annotation> annotation : javaClass.getAnnotations())
				{
					if("org.sodeac.common.annotation.GenerateBow".equals(annotation.getAnnotationType().getQualifiedName()))
					{
						for(Entry<String,CtExpression> valEntry : annotation.getValues().entrySet())
						{
							if("beanLikeSetters".equals(valEntry.getKey()))
							{
								try
								{
									beanLikeSetters = Boolean.parseBoolean(valEntry.getValue().toString().trim());
								}
								catch (Exception e) {}
							}
							
							if("name".equals(valEntry.getKey()))
							{
								try
								{
									if((valEntry.getValue() != null) && (valEntry.getValue().toString() != null) && (! valEntry.getValue().toString().isEmpty()) && (! valEntry.getValue().toString().equals("\"\"")))
									{
										typeName = valEntry.getValue().toString();
										if(typeName.startsWith("\""))
										{
											typeName = typeName.substring(1);
										}
										if(typeName.endsWith("\""))
										{
											typeName = typeName.substring(0,typeName.length() -1);
										}
									}
								}
								catch (Exception e) {}
							}
						}
					}
				}
				
				// TODO license header from POM
				
				StringBuilder classBuilder = new StringBuilder();
				
				classBuilder.append("package " + generatedPackageName + ";\n");
				classBuilder.append("\n");
				if("org.sodeac.common.typedtree.BranchNodeMetaModel".equals(superClass.getQualifiedName()))
				{
					classBuilder.append("public class " + generatedClassName + "<P extends org.sodeac.common.typedtree.BranchNodeToObjectWrapper> extends org.sodeac.common.typedtree.BranchNodeToObjectWrapper\n");
				}
				else
				{
					classBuilder.append("public class " + generatedClassName + "<P extends org.sodeac.common.typedtree.BranchNodeToObjectWrapper> extends " + getBOWPackage(superClass) + "." + getBOWName(superClass) + "<P>\n");
				}
				
				classBuilder.append("{\n");
				classBuilder.append("\n");
				classBuilder.append("\tpublic " + generatedClassName + "(org.sodeac.common.typedtree.BranchNode<?,? extends " + javaClass.getQualifiedName() + "> branchNode, org.sodeac.common.typedtree.BranchNodeToObjectWrapper parent)\n");
				classBuilder.append("\t{\n");
				classBuilder.append("\t\tsuper(branchNode, parent);\n");
				
				classBuilder.append("\t\t\n");
				
				classBuilder.append("\t\tthis." + instanceFactoryFieldName + " = " + generatedClassName + "." + factoriesFieldName + ".get(super.getModel().getClass());\n");
				classBuilder.append("\t\tif(this." + instanceFactoryFieldName + " == null)\n");
				classBuilder.append("\t\t{\n");
				classBuilder.append("\t\t\tthis." + instanceFactoryFieldName + " = new " + generatedClassName + ".FieldFactory_" + generatedPackageName.replace('.', '_') + "__" + generatedClassName + "(super.getModel());\n");
				classBuilder.append("\t\t\t" + generatedClassName + "." + factoriesFieldName + ".put(super.getModel().getClass(),this."+ instanceFactoryFieldName + ");\n");
				classBuilder.append("\t\t}\n");
				classBuilder.append("\t\t\n");
				
				List<CtFieldReference> definedFields = new ArrayList<CtFieldReference>(); 
				for(CtFieldReference field : javaClass.getAllFields())
				{
					if
					(!(
						"org.sodeac.common.typedtree.BranchNodeListType".equals(field.getType().getQualifiedName()) ||
				        "org.sodeac.common.typedtree.LeafNodeType".equals(field.getType().getQualifiedName()) ||
				        "org.sodeac.common.typedtree.BranchNodeType".equals(field.getType().getQualifiedName())
			        ))
					{
						continue;
					}
					
					CtTypeReference reference = (CtTypeReference)field.getType();
					if(reference.getActualTypeArguments().size() != 2)
					{
						continue;
					}
					
					if(! reference.getActualTypeArguments().get(0).getQualifiedName().equals(javaClass.getQualifiedName()))
					{
						continue;
					}
					definedFields.add(field);
				}
				
				int fieldIndex = 0;
				
				if(definedFields.size() < 2)
				{
					for(CtFieldReference field : definedFields)
					{
						classBuilder.append("\t\tthis._nodeField_" + field.getSimpleName() + " = this." + instanceFactoryFieldName + ".getNodeFieldTemplates()[" + fieldIndex++ + "];\n");
					}
				}
				else
				{
					classBuilder.append("\t\tNodeField[] nodeFieldTemplates =  this." + instanceFactoryFieldName + ".getNodeFieldTemplates();\n");
					for(CtFieldReference field : definedFields)
					{
						classBuilder.append("\t\tthis._nodeField_" + field.getSimpleName() + " = nodeFieldTemplates[" + fieldIndex++ + "];\n");
					}
				}
				classBuilder.append("\t\n");
				
				classBuilder.append("\t}\n");
				
				classBuilder.append("\n");
				
				classBuilder.append("\tprivate static final java.util.Map<java.lang.Class," + generatedClassName + ".FieldFactory_" + generatedPackageName.replace('.', '_') + "__" + generatedClassName + "> " + factoriesFieldName +" = java.util.Collections.synchronizedMap(new java.util.HashMap<>());\n");
				//classBuilder.append("\tprivate static final java.lang.Class<" + javaClass.getQualifiedName() + "> CLASS_" + packageName.replace('.', '_') + "__" + className + " = " + javaClass.getQualifiedName() + ".class;\n");
				
				classBuilder.append("\n");
				classBuilder.append("\tprivate " + generatedClassName + ".FieldFactory_" + generatedPackageName.replace('.', '_') + "__" + generatedClassName + " " + instanceFactoryFieldName + " = null;\n");
				
				classBuilder.append("\n");
				
				for(CtFieldReference field : definedFields)
				{
					classBuilder.append("\tprivate NodeField _nodeField_" + field.getSimpleName() + " = null;\n");
				}
				
				classBuilder.append("\n");
				
				classBuilder.append("\tpublic P getParent()\n");
				classBuilder.append("\t{\n");
				classBuilder.append("\t\treturn (P)super.__parent;\n");
				classBuilder.append("\t}\n");
				
				classBuilder.append("\t\n");
				
				classBuilder.append("\tprotected void dispose()\n");
				classBuilder.append("\t{\n");
				classBuilder.append("\t\tsuper.dispose();\n");
				classBuilder.append("\t\tthis." + instanceFactoryFieldName + " = null;\n");
				for(CtFieldReference field : definedFields)
				{
					classBuilder.append("\t\tthis._nodeField_" + field.getSimpleName() + " = null;\n");
				}
				classBuilder.append("\t}\n");
				
				classBuilder.append("\t\n");
				
				StringBuilder bowFactoryPart = new StringBuilder();
				bowFactoryPart.append("\tprotected void defineNestedBowFactories(java.util.Map<String,java.util.function.BiFunction<org.sodeac.common.typedtree.BranchNode, org.sodeac.common.typedtree.BranchNodeToObjectWrapper, org.sodeac.common.typedtree.BranchNodeToObjectWrapper>> factories)\n");
				bowFactoryPart.append("\t{\n");
				
				for(CtFieldReference field : definedFields)
				{
					CtTypeReference reference = (CtTypeReference)field.getType();
					if("org.sodeac.common.typedtree.LeafNodeType".equals(field.getType().getQualifiedName()))
					{
						CtTypeReference ref = reference.getActualTypeArguments().get(1);
						String typeQualifiedName = ref.getQualifiedName();
						
						classBuilder.append("\tpublic " + typeQualifiedName + " " + ("java.lang.Boolean".equals(typeQualifiedName) ? "is" : "get") 
								+ ( field.getSimpleName().length() == 1 ? 
										field.getSimpleName().toUpperCase() : 
										field.getSimpleName().substring(0, 1).toUpperCase()  + field.getSimpleName().substring(1)
								  ) + 
								"()\n");
						classBuilder.append("\t{\n");
						classBuilder.append("\t\treturn (" + typeQualifiedName + ") super.getLeafNodeValue(this._nodeField_" + field.getSimpleName() + ");\n");
						classBuilder.append("\t}\n");
						
						classBuilder.append("\t\n");
						
						classBuilder.append("\tpublic " + (beanLikeSetters ?  "void" : (generatedClassName + "<P>") ) + " set" 
								+ ( field.getSimpleName().length() == 1 ? 
										field.getSimpleName().toUpperCase() : 
										field.getSimpleName().substring(0, 1).toUpperCase()  + field.getSimpleName().substring(1)
								  ) + 
								"(" + typeQualifiedName + " " + field.getSimpleName() + ")\n");
						classBuilder.append("\t{\n");
						classBuilder.append("\t\tsuper.setLeafNodeValue(this._nodeField_" + field.getSimpleName() + ", " + field.getSimpleName() + ");\n");
						if(! beanLikeSetters)
						{
							classBuilder.append("\t\treturn this;\n");
						}
						classBuilder.append("\t}\n");
						
						classBuilder.append("\t\n");
						
					}
					else if("org.sodeac.common.typedtree.BranchNodeType".equals(field.getType().getQualifiedName()))
					{
						CtTypeReference ref = reference.getActualTypeArguments().get(1);
						String typeQualifiedName = ref.getQualifiedName();
						
						classBuilder.append("\tpublic " + getBOWName(typeQualifiedName) + "<" + getBOWName(javaClass.getQualifiedName()) + "<P>> get" 
								+ ( field.getSimpleName().length() == 1 ? 
										field.getSimpleName().toUpperCase() : 
										field.getSimpleName().substring(0, 1).toUpperCase()  + field.getSimpleName().substring(1)
								  ) + 
								"()\n");
						classBuilder.append("\t{\n");
						classBuilder.append("\t\treturn (" + getBOWName(typeQualifiedName) + ") super.getBranchNode(this._nodeField_" + field.getSimpleName() + ").getBow();\n");
						classBuilder.append("\t}\n");
						
						classBuilder.append("\t\n");
						
						classBuilder.append("\tpublic " + getBOWName(typeQualifiedName) + "<" + getBOWName(javaClass.getQualifiedName()) + "<P>> create" 
								+ ( field.getSimpleName().length() == 1 ? 
										field.getSimpleName().toUpperCase() : 
										field.getSimpleName().substring(0, 1).toUpperCase()  + field.getSimpleName().substring(1)
								  ) + 
								"()\n");
						classBuilder.append("\t{\n");
						classBuilder.append("\t\treturn (" + getBOWName(typeQualifiedName) + ") super.createBranchNode(this._nodeField_" + field.getSimpleName() + ").getBow();\n");
						classBuilder.append("\t}\n");
						
						classBuilder.append("\t\n");
					}
					else if("org.sodeac.common.typedtree.BranchNodeListType".equals(field.getType().getQualifiedName()))
					{
						CtTypeReference ref = reference.getActualTypeArguments().get(1);
						String typeQualifiedName = ref.getQualifiedName();
						
						classBuilder.append("\tpublic " + getBOWName(typeQualifiedName) + "<" + getBOWName(javaClass.getQualifiedName()) + "<P>> createOneOf" 
								+ ( field.getSimpleName().length() == 1 ? 
										field.getSimpleName().toUpperCase() : 
										field.getSimpleName().substring(0, 1).toUpperCase()  + field.getSimpleName().substring(1)
								  ) + 
								"()\n");
						classBuilder.append("\t{\n");
						classBuilder.append("\t\treturn (" + getBOWName(typeQualifiedName) + ") super.createBranchNodeItem(this._nodeField_" + field.getSimpleName() + ").getBow();\n");
						classBuilder.append("\t}\n");
						
						classBuilder.append("\t\n");
						
						classBuilder.append("\tpublic boolean removeFrom" 
								+ ( field.getSimpleName().length() == 1 ? 
										field.getSimpleName().toUpperCase() : 
										field.getSimpleName().substring(0, 1).toUpperCase()  + field.getSimpleName().substring(1)
								  ) + 
								"("+ getBOWName(typeQualifiedName) + "<" + getBOWName(javaClass.getQualifiedName()) +"<P>> nestedBow)\n");
						classBuilder.append("\t{\n");
						classBuilder.append("\t\treturn super.removeBranchNodeItem(this._nodeField_" + field.getSimpleName() + ", nestedBow);\n");
						classBuilder.append("\t}\n");
						
						classBuilder.append("\t\n");
						
						classBuilder.append("\tpublic java.util.List<"+ getBOWName(typeQualifiedName) + "<" + getBOWName(javaClass.getQualifiedName()) + "<P>>> getUnmodifiable" 
								+ ( field.getSimpleName().length() == 1 ? 
										field.getSimpleName().toUpperCase() : 
										field.getSimpleName().substring(0, 1).toUpperCase()  + field.getSimpleName().substring(1)
								  ) + 
								"()\n");
						classBuilder.append("\t{\n");
						classBuilder.append("\t\treturn (java.util.List)super.getBowList(this._nodeField_" + field.getSimpleName() + ");\n");
						classBuilder.append("\t}\n");
						
						classBuilder.append("\t\n");
						
						classBuilder.append("\tpublic java.util.stream.Stream<"+ getBOWName(typeQualifiedName) + "<" + getBOWName(javaClass.getQualifiedName()) + "<P>>> getStreamed" 
								+ ( field.getSimpleName().length() == 1 ? 
										field.getSimpleName().toUpperCase() : 
										field.getSimpleName().substring(0, 1).toUpperCase()  + field.getSimpleName().substring(1)
								  ) + 
								"()\n");
						classBuilder.append("\t{\n");
						classBuilder.append("\t\treturn (java.util.stream.Stream)super.getBowStream(this._nodeField_" + field.getSimpleName() + ");\n");
						classBuilder.append("\t}\n");
						
						classBuilder.append("\t\n");
					}
					
					if("org.sodeac.common.typedtree.BranchNodeType".equals(field.getType().getQualifiedName()) || "org.sodeac.common.typedtree.BranchNodeListType".equals(field.getType().getQualifiedName()))
					{
						CtTypeReference ref = reference.getActualTypeArguments().get(1);
						String typeQualifiedName = ref.getQualifiedName();
						
						bowFactoryPart.append("\t\tfactories.put(\"" + field.getSimpleName() + "\", (n,p) -> new  " + getBOWName(typeQualifiedName) + "(n, p));\n");
					}
				}
				
				bowFactoryPart.append("\t}\n");
				
				//classBuilder.append(bowFactoryPart.toString());
				classBuilder.append("\t\n");
				
				classBuilder.append("\t\n");
				classBuilder.append("\tprotected org.sodeac.common.typedtree.BranchNodeToObjectWrapper createNestedBow(int nodeTypeIndex, org.sodeac.common.typedtree.INodeType nodeType, org.sodeac.common.typedtree.BranchNode branchNode)\n");
				classBuilder.append("\t{\n");
				classBuilder.append("\t\tif(branchNode.getParentNode().getBow() != this)\n");
				classBuilder.append("\t\t{\n");
				classBuilder.append("\t\t\tthrow new java.lang.IllegalStateException(\"parent bow is wrong\");\n");
				classBuilder.append("\t\t}\n");
				classBuilder.append("\t\torg.sodeac.common.typedtree.BranchNodeToObjectWrapper newstedBow = this." + instanceFactoryFieldName + ".createNestedBow(nodeTypeIndex, nodeType, branchNode);\n");
				classBuilder.append("\t\tif(newstedBow == null)\n");
				classBuilder.append("\t\t{\n");
				classBuilder.append("\t\t\treturn super.createNestedBow(nodeTypeIndex, nodeType, branchNode);\n");
				classBuilder.append("\t\t}\n");
				classBuilder.append("\t\treturn newstedBow;\n");
				classBuilder.append("\t}\n");
				
				classBuilder.append("\t\n");
				
				if(! "org.sodeac.common.typedtree.BranchNodeMetaModel".equals(superClass.getQualifiedName()))
				{
					classBuilder.append("\tpublic " + getBOWName(javaClass.getQualifiedName()) + "<" + getBOWName(javaClass.getQualifiedName()) + "<P>> backupType" + typeName.substring(0, 1).toUpperCase()  + typeName.substring(1) + "()\n");
					classBuilder.append("\t{\n");
					classBuilder.append("\t\treturn (" + getBOWName(javaClass.getQualifiedName()) + ") this;\n");
					classBuilder.append("\t}\n");
				}
				
				classBuilder.append("\tpublic P restoreType()\n");
				classBuilder.append("\t{\n");
				classBuilder.append("\t\treturn getParent();\n");
				classBuilder.append("\t}\n");
				
				classBuilder.append("\t\n");
				
				classBuilder.append("\tprivate static class FieldFactory_" + generatedPackageName.replace('.', '_') + "__" + generatedClassName + " \n");
				classBuilder.append("\t{\n");
				classBuilder.append("\t\tprivate FieldFactory_" + generatedPackageName.replace('.', '_') + "__" + generatedClassName + "(org.sodeac.common.typedtree.BranchNodeMetaModel model)\n");
				classBuilder.append("\t\t{\n");
				classBuilder.append("\t\t\tsuper();\n");
				classBuilder.append("\t\t\tthis.model = model;\n");
				classBuilder.append("\t\t\tthis.nodeFieldTemplates = new NodeField[" + definedFields.size() + "];\n");
				classBuilder.append("\t\t\t\n");
				
				int index = 0;
				for(CtFieldReference field : definedFields)
				{
					classBuilder.append("\t\t\tthis.nodeFieldTemplates[" + index++ +"] = new NodeField(model.getNodeTypeIndexByClass().get("+ javaClass.getQualifiedName() + "." + field.getSimpleName() +")," + javaClass.getQualifiedName() + "." + field.getSimpleName() + ");\n");
				}
				
				classBuilder.append("\t\t\t\n");
				classBuilder.append("\t\t\tthis.nestedBeanFactories = new NestedPowFactoryCache[model.getNodeTypeList().size()];\n");
				classBuilder.append("\t\t\tfor(int i = 0; i < this.nestedBeanFactories.length; i++)\n");
				classBuilder.append("\t\t\t{\n");
				classBuilder.append("\t\t\t\tthis.nestedBeanFactories[i] = null;\n");
				classBuilder.append("\t\t\t}\n");
				classBuilder.append("\t\t\t\n");
				
				index = 0;
				for(CtFieldReference field : definedFields)
				{
					int i = index++;
					CtTypeReference reference = (CtTypeReference)field.getType();
					if("org.sodeac.common.typedtree.LeafNodeType".equals(field.getType().getQualifiedName()))
					{
						continue;
					}
					CtTypeReference ref = reference.getActualTypeArguments().get(1);
					String typeQualifiedName = ref.getQualifiedName();
					classBuilder.append("\t\t\tthis.nestedBeanFactories[this.nodeFieldTemplates[" + i + "].getNodeTypeIndex()] = new NestedPowFactoryCache(this.nodeFieldTemplates[" + i + "],(n,p) -> new  " + getBOWName(typeQualifiedName) + "(n, p)); \n");
				}
				classBuilder.append("\t\t\t\n");
				
				classBuilder.append("\t\t}\n");
				
				classBuilder.append("\t\t\n");
				classBuilder.append("\t\tprivate org.sodeac.common.typedtree.BranchNodeMetaModel model = null;\n");
				classBuilder.append("\t\tprivate NodeField[] nodeFieldTemplates = null;\n");
				classBuilder.append("\t\tprivate NestedPowFactoryCache[] nestedBeanFactories = null;\n");
				classBuilder.append("\t\t\n");
				classBuilder.append("\t\n");
				classBuilder.append("\t\tprivate org.sodeac.common.typedtree.BranchNodeToObjectWrapper createNestedBow(int nodeTypeIndex, org.sodeac.common.typedtree.INodeType nodeType, org.sodeac.common.typedtree.BranchNode branchNode)\n");
				classBuilder.append("\t\t{\n");
				classBuilder.append("\t\t\tNestedPowFactoryCache factory = this.nestedBeanFactories[nodeTypeIndex];\n");
				classBuilder.append("\t\t\tif(factory == null)\n");
				classBuilder.append("\t\t\t{\n");
				classBuilder.append("\t\t\t\treturn null;\n");
				classBuilder.append("\t\t\t}\n");
				classBuilder.append("\t\t\tif(factory.getNodeField().getNodeType() != nodeType)\n");
				classBuilder.append("\t\t\t{\n");
				classBuilder.append("\t\t\t\tthrow new java.lang.IllegalStateException(\"index of nested bean is wrong\");\n");
				classBuilder.append("\t\t\t}\n");
				classBuilder.append("\t\t\tif(branchNode.getNodeType() != nodeType)\n");
				classBuilder.append("\t\t\t{\n");
				classBuilder.append("\t\t\t\tthrow new java.lang.IllegalStateException(\"mismatch between nodetype and node\");\n");
				classBuilder.append("\t\t\t}\n");
				classBuilder.append("\t\t\treturn factory.getFactory().apply(branchNode, branchNode.getParentNode().getBow());\n");
				classBuilder.append("\t\t}\n");
				classBuilder.append("\t\n");
				classBuilder.append("\t\tprivate org.sodeac.common.typedtree.BranchNodeMetaModel getModel()\n");
				classBuilder.append("\t\t{\n");
				classBuilder.append("\t\t\treturn model;\n");
				classBuilder.append("\t\t}\n");
				classBuilder.append("\t\t\n");
				classBuilder.append("\t\tprivate NodeField[] getNodeFieldTemplates()\n");
				classBuilder.append("\t\t{\n");
				classBuilder.append("\t\t\treturn nodeFieldTemplates;\n");
				classBuilder.append("\t\t}\n");
				classBuilder.append("\t\t\n");
				classBuilder.append("\t}\n");
				
				
				classBuilder.append("}\n");
				
				
				System.out.println("Generate BOW " + generatedPackageName  + "." + generatedClassName);
				
				File outputDirFile = new File(sourceRoot,generatedPackageName.replace('.', '/'));
				if(!outputDirFile.exists())
				{
					outputDirFile.mkdirs();
				}
				File outputFile = new File(outputDirFile,generatedClassName +".java");
				FileOutputStream fos = new FileOutputStream(outputFile);
				try
				{
					fos.write(classBuilder.toString().getBytes());
				}
				finally 
				{
					fos.close();
				}
			}
		}
		catch (Exception e) 
		{
			throw new MojoExecutionException("error generate bows ",e); 
		}
		
		this.project.addCompileSourceRoot( sourceRoot.getAbsolutePath() );
	}
	
	private String getBOWPackage(CtClass javaClass)
	{
		// TODO overwrites by Annotation
		
		return javaClass.getPackage().getQualifiedName();
	}
	
	private String getBOWName(CtClass javaClass)
	{
		// TODO overwrites by Annotation
		
		String className = javaClass.getQualifiedName().substring(javaClass.getPackage().getQualifiedName().length() + 1);
		return getBOWName(className);
	}
	
	private String getBOWName(String className)
	{
		if(className.endsWith("NodeType") && className.length() > "NodeType".length())
		{
			return className.substring(0, className.length() - "NodeType".length()) + "Bow";
		}
		if(className.endsWith("Type") && className.length() > "Type".length())
		{
			return className.substring(0, className.length() - "Type".length()) + "Bow";
		}
		return className + "Bow";
	}
	
	private String getBOWFactoryPackage(CtClass javaClass)
	{
		// TODO overwrites by Annotation
		
		return javaClass.getPackage().getQualifiedName();
	}
	
	private String getBOWFactoryName(CtClass javaClass)
	{
		// TODO overwrites by Annotation
		
		String className = javaClass.getQualifiedName().substring(javaClass.getPackage().getQualifiedName().length() + 1); //javaClass.getSimpleName()
		
		if(className.endsWith("TreeMetaModel") && className.length() > "TreeMetaModel".length())
		{
			return className.substring(0, className.length() - "TreeMetaModel".length()) + "BowFactory";
		}
		if(className.endsWith("MetaModel") && className.length() > "MetaModel".length())
		{
			return className.substring(0, className.length() - "MetaModel".length()) + "BowFactory";
		}
		if(className.endsWith("TreeModel") && className.length() > "TreeModel".length())
		{
			return className.substring(0, className.length() - "TreeModel".length()) + "BowFactory";
		}
		if(className.endsWith("Model") && className.length() > "Model".length())
		{
			return className.substring(0, className.length() - "Model".length()) + "BowFactory";
		}
		return className + "BowFactory";
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

}
