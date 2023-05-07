/*
 * Copyright 2023 DocGPT Project Authors. Licensed under Apache-2.0.
 */
package io.docgpt.cmd;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.PackageDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.visitor.VoidVisitorAdapter;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JavaParserTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;
import io.docgpt.parse.ClassParser;
import io.docgpt.parse.CodeContext;
import io.docgpt.parse.MethodParser;
import io.docgpt.prompt.ClassPrompt;
import io.docgpt.prompt.MethodPrompt;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang3.StringUtils;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import static io.docgpt.cmd.TerminalService.terminal;


/**
 * @author masaimu
 * @version 2023-05-06 21:46:00
 */
public class LoadHandler extends CmdHandler {
  static Options options = new Options();

  CommandLine commandLine;

  JavaParser javaParser;

  static {
    options.addOption("d", "directory", true, "java file directory.");
  }

  public void parseOption(String[] args) throws ParseException {
    this.commandLine = parser.parse(options, args);
  }

  @Override
  public void run() {
    handler();
  }

  public void handler() {
    String stopMsg = " ";
    try {
      String directory = StringUtils.EMPTY;

      if (commandLine.hasOption("d")) {
        directory = commandLine.getOptionValue("d");
      }
      File dir = new File(directory);
      if (!dir.isDirectory()) {
        setWarnSignal(directory + " is not directory");
        return;
      }
      CodeContext codeContext = TerminalService.load(dir);
      setInfoSignal("Begin to load project files...");
      codeContext.loadProjects(dir);
      setInfoSignal(
          String.format("There are a total of %d Java files, and parsing is now starting...",
              codeContext.getFileSize()));
      init(codeContext);
      setInfoSignal("Begin to parse Java files...");
      preLoad(codeContext);
      stopMsg = "The Java code parsing has been completed.";
      CommandFactory.setCodeContext(codeContext);
    } finally {
      setStopSignal(stopMsg);
    }
  }

  public void init(CodeContext codeContext) {
    // Create a symbol resolver using a ReflectionTypeSolver
    ReflectionTypeSolver reflectionTypeSolver = new ReflectionTypeSolver();
    JavaSymbolSolver symbolSolver = new JavaSymbolSolver(reflectionTypeSolver);

    // Create a parser configuration with the symbol resolver
    ParserConfiguration parserConfiguration = new ParserConfiguration();
    parserConfiguration.setSymbolResolver(symbolSolver);

    javaParser = new JavaParser(parserConfiguration);

    setInfoSignal("Begin to parse packages...");
    packageLoad(codeContext);

    CombinedTypeSolver typeSolver = new CombinedTypeSolver();

    typeSolver.add(reflectionTypeSolver);
    for (String folder : codeContext.sourceDirs) {
      typeSolver.add(new JavaParserTypeSolver(folder));
    }

    symbolSolver = new JavaSymbolSolver(typeSolver);
    parserConfiguration.setSymbolResolver(symbolSolver);

    javaParser = new JavaParser(parserConfiguration);
  }

  public void packageLoad(CodeContext context) {
    int size = context.getFileSize();
    for (int i = 0; i < size; i++) {
      File javaFile = context.javaFiles.get(i);
      try {
        PackageVisitor packageVisitor = new PackageVisitor();
        ParseResult<CompilationUnit> parseResult = javaParser.parse(javaFile);
        if (!parseResult.isSuccessful()) {
          System.err.println("fail to parse " + javaFile.getName());
        }
        CompilationUnit unit = parseResult.getResult().get();
        unit.accept(packageVisitor, null);
        String packageName = packageVisitor.packageName;
        String packagePath = packageName.replace(".", File.separator);
        String sourceDirectory = javaFile.getPath().split(packagePath)[0];
        context.sourceDirs.add(sourceDirectory);
        setProgressSignal(size, i);
      } catch (Exception e) {
        System.err
            .println("fail to parse " + javaFile.getAbsolutePath() + " for " + e.getMessage());
        e.printStackTrace();
      }
    }
  }

  public void preLoad(CodeContext context) {
    int size = context.getFileSize();
    for (int i = 0; i < size; i++) {
      File javaFile = context.javaFiles.get(i);
      try {
        ClassVisitor classVisitor = new ClassVisitor();

        ParseResult<CompilationUnit> parseResult = javaParser.parse(javaFile);
        if (!parseResult.isSuccessful()) {
          continue;
          // System.err.println("fail to parse " + javaFile.getName());
        }
        CompilationUnit unit = parseResult.getResult().get();
        unit.accept(classVisitor, null);
        ClassPrompt classPrompt = classVisitor.classPrompt;
        if (!CollectionUtils.isEmpty(classPrompt.getClassAnnotations())) {
          for (MethodPrompt methodPrompt : classPrompt.getMethodCache().values()) {
            methodPrompt.addAnnotations(classPrompt.getClassAnnotations());
          }
        }
        context.cache.put(classPrompt.getFullyQualifiedName(), classPrompt);
        List<String> fullNames =
            context.nameCache.computeIfAbsent(classPrompt.getSimpleName(), k -> new ArrayList<>());
        fullNames.add(classPrompt.getFullyQualifiedName());
        setProgressSignal(size, i);
      } catch (Exception e) {
        System.err
            .println("fail to parse " + javaFile.getAbsolutePath() + " for " + e.getMessage());
        e.printStackTrace();
      }
    }
  }

  private static class PackageVisitor extends VoidVisitorAdapter<Void> {
    String packageName;

    @Override
    public void visit(PackageDeclaration pd, Void arg) {
      packageName = pd.getName().asString();
      super.visit(pd, arg);
    }
  }

  // 自定义访问者，用于访问方法
  private static class ClassVisitor extends VoidVisitorAdapter<Void> {
    ClassPrompt classPrompt = new ClassPrompt();

    @Override
    public void visit(ClassOrInterfaceDeclaration cid, Void arg) {
      try {
        ClassParser.parseSimpleName(cid, classPrompt);
        ClassParser.parseFullyQualifiedName(cid, classPrompt);
        ClassParser.parseAnnotation(cid, classPrompt);
        ClassParser.parseField(cid, classPrompt);
      } finally {
        super.visit(cid, arg);
      }
    }

    @Override
    public void visit(MethodDeclaration md, Void arg) {
      try {
        if (!isApiAnnotations(md.getAnnotations())) {
          return;
        }
        MethodPrompt methodPrompt = new MethodPrompt();
        methodPrompt.setClassPrompt(classPrompt);
        MethodParser.parseDeclaration(md, methodPrompt);
        MethodParser.parseSimpleName(md, methodPrompt);
        MethodParser.parseParameter(md, methodPrompt);
        MethodParser.parseComment(md, methodPrompt);
        MethodParser.parseAnnotation(md, methodPrompt);
        MethodParser.parseCode(md, methodPrompt);
        MethodParser.parseResponse(md, methodPrompt);
        classPrompt.cacheMethod(methodPrompt);
      } finally {
        super.visit(md, arg);
      }
    }

    private boolean isApiAnnotations(NodeList<AnnotationExpr> annotations) {
      if (CollectionUtils.isEmpty(annotations)) {
        return false;
      }
      for (AnnotationExpr annotationExpr : annotations) {
        String name = annotationExpr.getName().asString();
        if (name.contains("Mapping")) {
          return true;
        }
      }
      return false;
    }
  }
}