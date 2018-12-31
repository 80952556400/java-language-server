package org.javacs;

import com.google.gson.*;
import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.sun.source.doctree.DocCommentTree;
import com.sun.source.doctree.DocTree;
import com.sun.source.doctree.ParamTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.TreePath;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.StringJoiner;
import java.util.UUID;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;
import org.javacs.lsp.*;

class JavaLanguageServer extends LanguageServer {
    private static final Logger LOG = Logger.getLogger("main");

    // TODO allow multiple workspace roots
    private Path workspaceRoot;
    private SourcePath sourcePath;
    private final LanguageClient client;
    private Set<String> externalDependencies = Set.of();
    private Set<Path> classPath = Set.of();

    JavaCompilerService compiler;
    private final Map<URI, VersionedContent> activeDocuments = new HashMap<>();

    private static int severity(Diagnostic.Kind kind) {
        switch (kind) {
            case ERROR:
                return DiagnosticSeverity.Error;
            case WARNING:
            case MANDATORY_WARNING:
                return DiagnosticSeverity.Warning;
            case NOTE:
            case OTHER:
            default:
                return DiagnosticSeverity.Information;
        }
    }

    private static Position position(String content, long offset) {
        int line = 0, column = 0;
        for (int i = 0; i < offset; i++) {
            if (content.charAt(i) == '\n') {
                line++;
                column = 0;
            } else column++;
        }
        return new Position(line, column);
    }

    void publishDiagnostics(Collection<URI> files, List<Diagnostic<? extends JavaFileObject>> javaDiagnostics) {
        for (var f : files) {
            List<org.javacs.lsp.Diagnostic> ds = new ArrayList<>();
            for (var j : javaDiagnostics) {
                if (j.getSource() == null) {
                    LOG.warning("No source in warning " + j.getMessage(null));
                    continue;
                }

                var uri = j.getSource().toUri();
                if (uri.equals(f)) {
                    var content = contents(uri).content;
                    var start = position(content, j.getStartPosition());
                    var end = position(content, j.getEndPosition());
                    var d = new org.javacs.lsp.Diagnostic();
                    d.severity = severity(j.getKind());
                    d.range = new Range(start, end);
                    d.code = j.getCode();
                    d.message = j.getMessage(null);
                    ds.add(d);
                }
            }
            client.publishDiagnostics(new PublishDiagnosticsParams(f, ds));
        }
    }

    void lint(Collection<URI> uris) {
        var message = new StringJoiner(", ");
        for (var uri : uris) {
            var path = uri.getPath();
            var name = Paths.get(path).getFileName();
            message.add(name.toString());
        }
        LOG.info("Lint " + message);
        publishDiagnostics(uris, compiler.compileBatch(uris).lint());
    }

    private static final Gson gson = new Gson();

    private void javaStartProgress(JavaStartProgressParams params) {
        client.customNotification("java/startProgress", gson.toJsonTree(params));
    }

    private void javaReportProgress(JavaReportProgressParams params) {
        client.customNotification("java/reportProgress", gson.toJsonTree(params));
    }

    private void javaEndProgress() {
        client.customNotification("java/endProgress", JsonNull.INSTANCE);
    }

    private JavaCompilerService createCompiler() {
        Objects.requireNonNull(workspaceRoot, "Can't create compiler because workspaceRoot has not been initialized");

        javaStartProgress(new JavaStartProgressParams("Configure javac"));
        javaReportProgress(new JavaReportProgressParams("Finding source roots"));

        // If classpath is specified by the user, don't infer anything
        if (!classPath.isEmpty()) {
            javaEndProgress();
            return new JavaCompilerService(
                    sourcePath.sourceRoots(), sourcePath::allJavaFiles, classPath, Collections.emptySet());
        }
        // Otherwise, combine inference with user-specified external dependencies
        else {
            var infer = new InferConfig(workspaceRoot, externalDependencies);

            javaReportProgress(new JavaReportProgressParams("Inferring class path"));
            var classPath = infer.classPath();

            javaReportProgress(new JavaReportProgressParams("Inferring doc path"));
            var docPath = infer.buildDocPath();

            javaEndProgress();
            return new JavaCompilerService(sourcePath.sourceRoots(), sourcePath::allJavaFiles, classPath, docPath);
        }
    }

    void setExternalDependencies(Set<String> externalDependencies) {
        var changed =
                this.externalDependencies.isEmpty()
                        != externalDependencies.isEmpty(); // TODO shouldn't this be any change?
        this.externalDependencies = externalDependencies;
        if (changed) this.compiler = createCompiler();
    }

    void setClassPath(Set<Path> classPath) {
        var changed = this.classPath.isEmpty() != classPath.isEmpty(); // TODO shouldn't this be any change?
        this.classPath = classPath;
        if (changed) this.compiler = createCompiler();
    }

    @Override
    public InitializeResult initialize(InitializeParams params) {
        this.workspaceRoot = Paths.get(params.rootUri);
        this.sourcePath = new SourcePath(Set.of(workspaceRoot));

        var c = new JsonObject();
        c.addProperty("textDocumentSync", 2); // Incremental
        c.addProperty("hoverProvider", true);
        var completionOptions = new JsonObject();
        completionOptions.addProperty("resolveProvider", true);
        var triggerCharacters = new JsonArray();
        triggerCharacters.add(".");
        completionOptions.add("triggerCharacters", triggerCharacters);
        c.add("completionProvider", completionOptions);
        var signatureHelpOptions = new JsonObject();
        var signatureTrigger = new JsonArray();
        signatureTrigger.add("(");
        signatureTrigger.add(",");
        signatureHelpOptions.add("triggerCharacters", signatureTrigger);
        c.add("signatureHelpProvider", signatureHelpOptions);
        c.addProperty("referencesProvider", true);
        c.addProperty("definitionProvider", true);
        c.addProperty("workspaceSymbolProvider", true);
        c.addProperty("documentSymbolProvider", true);
        c.addProperty("documentFormattingProvider", true);
        var codeLensOptions = new JsonObject();
        codeLensOptions.addProperty("resolveProvider", true);
        c.add("codeLensProvider", codeLensOptions);

        return new InitializeResult(c);
    }

    @Override
    public void initialized() {
        this.compiler = createCompiler();

        // Register for didChangeWatchedFiles notifications
        var options = new JsonObject();
        var watchers = new JsonArray();
        var watchJava = new JsonObject();
        watchJava.addProperty("globPattern", "**/*.java");
        watchers.add(watchJava);
        options.add("watchers", watchers);
        client.registerCapability("workspace/didChangeWatchedFiles", gson.toJsonTree(options));
    }

    @Override
    public void shutdown() {}

    public JavaLanguageServer(LanguageClient client) {
        this.client = client;
    }

    @Override
    public List<SymbolInformation> workspaceSymbols(WorkspaceSymbolParams params) {
        var list = new ArrayList<SymbolInformation>();
        for (var s : compiler.findSymbols(params.query, 50)) {
            var i = asSymbolInformation(s);
            list.add(i);
        }
        return list;
    }

    @Override
    public void didChangeConfiguration(DidChangeConfigurationParams change) {
        var settings = (JsonObject) change.settings;
        var java = settings.getAsJsonObject("java");

        var externalDependencies = java.getAsJsonArray("externalDependencies");
        var strings = new HashSet<String>();
        for (var each : externalDependencies) strings.add(each.getAsString());
        setExternalDependencies(strings);

        var classPath = java.getAsJsonArray("classPath");
        var paths = new HashSet<Path>();
        for (var each : classPath) paths.add(Paths.get(each.getAsString()).toAbsolutePath());
        setClassPath(paths);
    }

    @Override
    public void didChangeWatchedFiles(DidChangeWatchedFilesParams params) {
        var changed = false;
        var created = new HashSet<Path>();
        var deleted = new HashSet<Path>();
        for (var c : params.changes) {
            if (!isJavaFile(c.uri)) continue;
            var file = Paths.get(c.uri);
            switch (c.type) {
                case FileChangeType.Created:
                    created.add(file);
                    break;
                case FileChangeType.Changed:
                    if (sourcePath.update(file)) changed = true;
                    break;
                case FileChangeType.Deleted:
                    deleted.add(file);
                    break;
            }
        }
        if (sourcePath.create(created)) changed = true;
        if (sourcePath.delete(deleted)) changed = true;
        if (changed) this.compiler = createCompiler();
    }

    private Integer completionItemKind(Element e) {
        switch (e.getKind()) {
            case ANNOTATION_TYPE:
                return CompletionItemKind.Interface;
            case CLASS:
                return CompletionItemKind.Class;
            case CONSTRUCTOR:
                return CompletionItemKind.Constructor;
            case ENUM:
                return CompletionItemKind.Enum;
            case ENUM_CONSTANT:
                return CompletionItemKind.EnumMember;
            case EXCEPTION_PARAMETER:
                return CompletionItemKind.Variable;
            case FIELD:
                return CompletionItemKind.Field;
            case STATIC_INIT:
            case INSTANCE_INIT:
                return CompletionItemKind.Function;
            case INTERFACE:
                return CompletionItemKind.Interface;
            case LOCAL_VARIABLE:
                return CompletionItemKind.Variable;
            case METHOD:
                return CompletionItemKind.Method;
            case PACKAGE:
                return CompletionItemKind.Module;
            case PARAMETER:
                return CompletionItemKind.Variable;
            case RESOURCE_VARIABLE:
                return CompletionItemKind.Variable;
            case TYPE_PARAMETER:
                return CompletionItemKind.TypeParameter;
            case OTHER:
            default:
                return null;
        }
    }

    private boolean isMemberOfObject(Element e) {
        var parent = e.getEnclosingElement();
        if (parent instanceof TypeElement) {
            var type = (TypeElement) parent;
            return type.getQualifiedName().contentEquals("java.lang.Object");
        }
        return false;
    }

    /** Cache of completions from the last call to `completion` */
    private final Map<String, Completion> lastCompletions = new HashMap<>();

    @Override
    public Optional<CompletionList> completion(TextDocumentPositionParams position) {
        var started = Instant.now();
        var uri = position.textDocument.uri;
        if (!isJavaFile(uri)) return Optional.empty();
        var content = contents(uri).content;
        var line = position.position.line + 1;
        var column = position.position.character + 1;
        lastCompletions.clear();
        // Figure out what kind of completion we want to do
        var maybeCtx = compiler.parseFile(uri, content).completionContext(line, column);
        if (!maybeCtx.isPresent()) {
            var items = new ArrayList<CompletionItem>();
            for (var name : CompileFocus.TOP_LEVEL_KEYWORDS) {
                var i = new CompletionItem();
                i.label = name;
                i.kind = CompletionItemKind.Keyword;
                i.detail = "keyword";
                items.add(i);
            }
            return Optional.of(new CompletionList(true, items));
        }
        // Compile again, focusing on a region that depends on what type of completion we want to do
        var ctx = maybeCtx.get();
        var focus = compiler.compileFocus(uri, content, ctx.line, ctx.character);
        // Do a specific type of completion
        List<Completion> cs;
        boolean isIncomplete;
        switch (ctx.kind) {
            case MemberSelect:
                cs = focus.completeMembers(false);
                isIncomplete = false;
                break;
            case MemberReference:
                cs = focus.completeMembers(true);
                isIncomplete = false;
                break;
            case Identifier:
                cs = focus.completeIdentifiers(ctx.inClass, ctx.inMethod, ctx.partialName);
                isIncomplete = cs.size() >= CompileFocus.MAX_COMPLETION_ITEMS;
                break;
            case Annotation:
                cs = focus.completeAnnotations(ctx.partialName);
                isIncomplete = cs.size() >= CompileFocus.MAX_COMPLETION_ITEMS;
                break;
            case Case:
                cs = focus.completeCases();
                isIncomplete = false;
                break;
            default:
                throw new RuntimeException("Unexpected completion context " + ctx.kind);
        }
        // Convert to CompletionItem
        var result = new ArrayList<CompletionItem>();
        for (var c : cs) {
            var i = new CompletionItem();
            var id = UUID.randomUUID().toString();
            i.data = new JsonPrimitive(id);
            lastCompletions.put(id, c);
            if (c.element != null) {
                i.label = c.element.getSimpleName().toString();
                i.kind = completionItemKind(c.element);
                // Detailed name will be resolved later, using docs to fill in method names
                if (!(c.element instanceof ExecutableElement)) {
                    i.detail = ShortTypePrinter.print(c.element.asType());
                }
                // TODO prioritize based on usage?
                if (isMemberOfObject(c.element)) {
                    i.sortText = 9 + i.label;
                } else {
                    i.sortText = 2 + i.label;
                }
            } else if (c.packagePart != null) {
                i.label = c.packagePart.name;
                i.kind = CompletionItemKind.Module;
                i.detail = c.packagePart.fullName;
                i.sortText = 2 + i.label;
            } else if (c.keyword != null) {
                i.label = c.keyword;
                i.kind = CompletionItemKind.Keyword;
                i.detail = "keyword";
                i.sortText = 3 + i.label;
            } else if (c.className != null) {
                i.label = Parser.lastName(c.className.name);
                i.kind = CompletionItemKind.Class;
                i.detail = c.className.name;
                if (c.className.isImported) {
                    i.sortText = 2 + i.label;
                } else {
                    i.sortText = 4 + i.label;
                }
            } else if (c.snippet != null) {
                i.label = c.snippet.label;
                i.kind = CompletionItemKind.Snippet;
                i.insertText = c.snippet.snippet;
                i.insertTextFormat = InsertTextFormat.Snippet;
                i.sortText = 1 + i.label;
            } else {
                throw new RuntimeException(c + " is not valid");
            }

            result.add(i);
        }
        // Log timing
        var elapsedMs = Duration.between(started, Instant.now()).toMillis();
        if (isIncomplete) LOG.info(String.format("Found %d items (incomplete) in %,d ms", result.size(), elapsedMs));
        else LOG.info(String.format("...found %d items in %,d ms", result.size(), elapsedMs));

        return Optional.of(new CompletionList(isIncomplete, result));
    }

    private String resolveDocDetail(MethodTree doc) {
        var args = new StringJoiner(", ");
        for (var p : doc.getParameters()) {
            args.add(p.getName());
        }
        return String.format("%s %s(%s)", doc.getReturnType(), doc.getName(), args);
    }

    private String resolveDefaultDetail(ExecutableElement method) {
        var args = new StringJoiner(", ");
        var missingParamNames =
                method.getParameters().stream().allMatch(p -> p.getSimpleName().toString().matches("arg\\d+"));
        for (var p : method.getParameters()) {
            if (missingParamNames) args.add(ShortTypePrinter.print(p.asType()));
            else args.add(p.getSimpleName().toString());
        }
        return String.format("%s %s(%s)", ShortTypePrinter.print(method.getReturnType()), method.getSimpleName(), args);
    }

    private String asMarkdown(List<? extends DocTree> lines) {
        var join = new StringJoiner("\n");
        for (var l : lines) join.add(l.toString());
        var html = join.toString();
        return Docs.htmlToMarkdown(html);
    }

    private String asMarkdown(DocCommentTree comment) {
        var lines = comment.getFirstSentence();
        return asMarkdown(lines);
    }

    private MarkupContent asMarkupContent(DocCommentTree comment) {
        var markdown = asMarkdown(comment);
        var content = new MarkupContent();
        content.kind = MarkupKind.Markdown;
        content.value = markdown;
        return content;
    }

    @Override
    public CompletionItem resolveCompletionItem(CompletionItem unresolved) {
        var idJson = (JsonPrimitive) unresolved.data;
        var id = idJson.getAsString();
        var cached = lastCompletions.get(id);
        if (cached == null) {
            LOG.warning("CompletionItem " + id + " was not in the cache");
            return unresolved;
        }
        if (cached.element != null) {
            if (cached.element instanceof ExecutableElement) {
                var method = (ExecutableElement) cached.element;
                var tree = compiler.docs().methodTree(method);
                var detail = tree.map(this::resolveDocDetail).orElse(resolveDefaultDetail(method));
                unresolved.detail = detail;

                var doc = compiler.docs().methodDoc(method);
                var markdown = doc.map(this::asMarkupContent);
                if (markdown.isPresent()) unresolved.documentation = markdown.get();
            } else if (cached.element instanceof TypeElement) {
                var type = (TypeElement) cached.element;
                var doc = compiler.docs().classDoc(type);
                var markdown = doc.map(this::asMarkupContent);
                if (markdown.isPresent()) unresolved.documentation = markdown.get();
            } else {
                LOG.info("Don't know how to look up docs for element " + cached.element);
            }
            // TODO constructors, fields
        } else if (cached.className != null) {
            var doc = compiler.docs().classDoc(cached.className.name);
            var markdown = doc.map(this::asMarkupContent);
            if (markdown.isPresent()) unresolved.documentation = markdown.get();
        }
        return unresolved;
    }

    private String hoverTypeDeclaration(TypeElement t) {
        var result = new StringBuilder();
        switch (t.getKind()) {
            case ANNOTATION_TYPE:
                result.append("@interface");
                break;
            case INTERFACE:
                result.append("interface");
                break;
            case CLASS:
                result.append("class");
                break;
            case ENUM:
                result.append("enum");
                break;
            default:
                LOG.warning("Don't know what to call type element " + t);
                result.append("???");
        }
        result.append(" ").append(ShortTypePrinter.print(t.asType()));
        var superType = ShortTypePrinter.print(t.getSuperclass());
        switch (superType) {
            case "Object":
            case "none":
                break;
            default:
                result.append(" extends ").append(superType);
        }
        return result.toString();
    }

    private String hoverCode(Element e) {
        if (e instanceof ExecutableElement) {
            var m = (ExecutableElement) e;
            return ShortTypePrinter.printMethod(m);
        } else if (e instanceof VariableElement) {
            var v = (VariableElement) e;
            return ShortTypePrinter.print(v.asType()) + " " + v;
        } else if (e instanceof TypeElement) {
            var t = (TypeElement) e;
            var lines = new StringJoiner("\n");
            lines.add(hoverTypeDeclaration(t) + " {");
            for (var member : t.getEnclosedElements()) {
                // TODO check accessibility
                if (member instanceof ExecutableElement || member instanceof VariableElement) {
                    lines.add("  " + hoverCode(member) + ";");
                } else if (member instanceof TypeElement) {
                    lines.add("  " + hoverTypeDeclaration((TypeElement) member) + " { /* removed */ }");
                }
            }
            lines.add("}");
            return lines.toString();
        } else return e.toString();
    }

    private Optional<String> hoverDocs(Element e) {
        if (e instanceof ExecutableElement) {
            var m = (ExecutableElement) e;
            return compiler.docs().methodDoc(m).map(this::asMarkdown);
        } else if (e instanceof TypeElement) {
            var t = (TypeElement) e;
            return compiler.docs().classDoc(t).map(this::asMarkdown);
        } else return Optional.empty();
    }

    private CompileFile hoverCache;

    private void updateHoverCache(URI uri, String contents) {
        if (hoverCache == null || !hoverCache.file.equals(uri) || !hoverCache.contents.equals(contents)) {
            LOG.info("File has changed since last hover, recompiling");
            hoverCache = compiler.compileFile(uri, contents);
        }
    }

    @Override
    public Optional<Hover> hover(TextDocumentPositionParams position) {
        // Compile entire file if it's changed since last hover
        var uri = position.textDocument.uri;
        if (!isJavaFile(uri)) return Optional.empty();
        var content = contents(uri).content;
        updateHoverCache(uri, content);

        // Find element undeer cursor
        var line = position.position.line + 1;
        var column = position.position.character + 1;
        var el = hoverCache.element(line, column);
        if (!el.isPresent()) return Optional.empty();

        // Add code hover message
        var result = new ArrayList<MarkedString>();
        var code = hoverCode(el.get());
        result.add(new MarkedString("java.hover", code));
        // Add docs hover message
        var docs = hoverDocs(el.get());
        if (docs.isPresent()) {
            result.add(new MarkedString("markdown", docs.get()));
        }

        return Optional.of(new Hover(result));
    }

    private List<ParameterInformation> signatureParamsFromDocs(MethodTree method, DocCommentTree doc) {
        var ps = new ArrayList<ParameterInformation>();
        var paramComments = new HashMap<String, String>();
        for (var tag : doc.getBlockTags()) {
            if (tag.getKind() == DocTree.Kind.PARAM) {
                var param = (ParamTree) tag;
                paramComments.put(param.getName().toString(), asMarkdown(param.getDescription()));
            }
        }
        for (var param : method.getParameters()) {
            var info = new ParameterInformation();
            var name = param.getName().toString();
            info.label = name;
            if (paramComments.containsKey(name)) {
                var markdown = paramComments.get(name);
                info.documentation = new MarkupContent("markdown", markdown);
            } else {
                var markdown = Objects.toString(param.getType(), "");
                info.documentation = new MarkupContent("markdown", markdown);
            }
            ps.add(info);
        }
        return ps;
    }

    private List<ParameterInformation> signatureParamsFromMethod(ExecutableElement e) {
        var missingParamNames = ShortTypePrinter.missingParamNames(e);
        var ps = new ArrayList<ParameterInformation>();
        for (var v : e.getParameters()) {
            var p = new ParameterInformation();
            if (missingParamNames) p.label = ShortTypePrinter.print(v.asType());
            else p.label = v.getSimpleName().toString();
            ps.add(p);
        }
        return ps;
    }

    private SignatureInformation asSignatureInformation(ExecutableElement e) {
        var i = new SignatureInformation();
        var ps = signatureParamsFromMethod(e);
        var doc = compiler.docs().methodDoc(e);
        var tree = compiler.docs().methodTree(e);
        if (doc.isPresent() && tree.isPresent()) ps = signatureParamsFromDocs(tree.get(), doc.get());
        var args = ps.stream().map(p -> p.label).collect(Collectors.joining(", "));
        var name = e.getSimpleName().toString();
        if (name.equals("<init>")) name = e.getEnclosingElement().getSimpleName().toString();
        i.label = name + "(" + args + ")";
        i.parameters = ps;
        return i;
    }

    private SignatureHelp asSignatureHelp(MethodInvocation invoke) {
        // TODO use docs to get parameter names
        var sigs = new ArrayList<SignatureInformation>();
        for (var e : invoke.overloads) {
            sigs.add(asSignatureInformation(e));
        }
        var activeSig = invoke.activeMethod.map(invoke.overloads::indexOf).orElse(0);
        return new SignatureHelp(sigs, activeSig, invoke.activeParameter);
    }

    @Override
    public Optional<SignatureHelp> signatureHelp(TextDocumentPositionParams position) {
        var uri = position.textDocument.uri;
        if (!isJavaFile(uri)) return Optional.empty();
        var content = contents(uri).content;
        var line = position.position.line + 1;
        var column = position.position.character + 1;
        var focus = compiler.compileFocus(uri, content, line, column);
        var help = focus.methodInvocation().map(this::asSignatureHelp);
        return help;
    }

    @Override
    public List<Location> gotoDefinition(TextDocumentPositionParams position) {
        var fromUri = position.textDocument.uri;
        if (!isJavaFile(fromUri)) return List.of();
        var fromLine = position.position.line + 1;
        var fromColumn = position.position.character + 1;
        var fromContent = contents(fromUri).content;
        LOG.info(String.format("Go-to-def at %s:%d...", fromUri, fromLine));
        var fromFocus = compiler.compileFocus(fromUri, fromContent, fromLine, fromColumn);
        var toEl = fromFocus.element();
        LOG.info(String.format("...looking for definition of `%s`", toEl));
        var toPath = fromFocus.path(toEl);
        if (!toPath.isPresent()) {
            LOG.info(String.format("...couldn't find declaring file, giving up"));
            return List.of();
        }
        var toUri = toPath.get().getCompilationUnit().getSourceFile().toUri();
        if (!isJavaFile(toUri)) {
            LOG.info(String.format("...declaring file %s isn't a .java file", toUri));
            return List.of();
        }
        var toContent = contents(toUri).content;
        var toFile = compiler.compileFile(toUri, toContent);
        // Figure out where in the file the definition is
        var toRange = toFile.range(toPath.get());
        if (!toRange.isPresent()) {
            LOG.info(String.format("Couldn't find `%s` in %s", toPath.get(), toUri));
            return List.of();
        }
        var to = new Location(toUri, toRange.get());
        return List.of(to);
    }

    class Progress implements ReportProgress, AutoCloseable {
        @Override
        public void start(String message) {
            javaStartProgress(new JavaStartProgressParams(message));
        }

        @Override
        public void progress(String message, int n, int total) {
            if (n == 0) {
                javaReportProgress(new JavaReportProgressParams(message));
            } else {
                var increment = percent(n, total) > percent(n - 1, total) ? 1 : 0;
                javaReportProgress(new JavaReportProgressParams(message, increment));
            }
        }

        private int percent(int n, int d) {
            double nD = n, dD = d;
            double ratio = nD / dD;
            return (int) (ratio * 100);
        }

        @Override
        public void close() {
            javaEndProgress();
        }
    }

    @Override
    public List<Location> findReferences(ReferenceParams position) {
        var toUri = position.textDocument.uri;
        if (!isJavaFile(toUri)) return List.of();
        var toContent = contents(toUri).content;
        var toLine = position.position.line + 1;
        var toColumn = position.position.character + 1;
        var toEl = compiler.compileFocus(toUri, toContent, toLine, toColumn).element();
        var fromFiles = compiler.potentialReferences(toEl);
        if (fromFiles.isEmpty()) return List.of();
        var batch = compiler.compileBatch(fromFiles);
        var fromTreePaths = batch.references(toEl);
        var result = new ArrayList<Location>();
        for (var path : fromTreePaths) {
            var fromUri = path.getCompilationUnit().getSourceFile().toUri();
            var fromRange = batch.range(path);
            if (!fromRange.isPresent()) {
                LOG.warning(String.format("Couldn't locate `%s`", path.getLeaf()));
                continue;
            }
            var from = new Location(fromUri, fromRange.get());
            result.add(from);
        }
        return result;
    }

    private ParseFile cacheParse;
    private URI cacheParseFile = URI.create("file:///NONE");;
    private int cacheParseVersion = -1;

    private void updateCachedParse(URI file) {
        if (file.equals(cacheParseFile) && contents(file).version == cacheParseVersion) return;
        LOG.info(String.format("Updating cached parse file to %s", file));
        var contents = contents(file);
        cacheParse = compiler.parseFile(file, contents.content);
        cacheParseFile = file;
        cacheParseVersion = contents.version;
    }

    @Override
    public List<SymbolInformation> documentSymbol(DocumentSymbolParams params) {
        var uri = params.textDocument.uri;
        if (!isJavaFile(uri)) return List.of();
        updateCachedParse(uri);
        var paths = cacheParse.documentSymbols();
        var infos = new ArrayList<SymbolInformation>();
        for (var p : paths) {
            infos.add(asSymbolInformation(p));
        }
        return infos;
    }

    static SymbolInformation asSymbolInformation(TreePath path) {
        var i = new SymbolInformation();
        var t = path.getLeaf();
        i.kind = asSymbolKind(t.getKind());
        i.name = symbolName(t);
        i.containerName = containerName(path);
        i.location = Parser.location(path);
        return i;
    }

    private static Integer asSymbolKind(Tree.Kind k) {
        switch (k) {
            case ANNOTATION_TYPE:
            case CLASS:
                return SymbolKind.Class;
            case ENUM:
                return SymbolKind.Enum;
            case INTERFACE:
                return SymbolKind.Interface;
            case METHOD:
                return SymbolKind.Method;
            case TYPE_PARAMETER:
                return SymbolKind.TypeParameter;
            case VARIABLE:
                // This method is used for symbol-search functionality,
                // where we only return fields, not local variables
                return SymbolKind.Field;
            default:
                return null;
        }
    }

    private static String containerName(TreePath path) {
        var parent = path.getParentPath();
        while (parent != null) {
            var t = parent.getLeaf();
            if (t instanceof ClassTree) {
                var c = (ClassTree) t;
                return c.getSimpleName().toString();
            } else if (t instanceof CompilationUnitTree) {
                var c = (CompilationUnitTree) t;
                return Objects.toString(c.getPackageName(), "");
            } else {
                parent = parent.getParentPath();
            }
        }
        return null;
    }

    private static String symbolName(Tree t) {
        if (t instanceof ClassTree) {
            var c = (ClassTree) t;
            return c.getSimpleName().toString();
        } else if (t instanceof MethodTree) {
            var m = (MethodTree) t;
            return m.getName().toString();
        } else if (t instanceof VariableTree) {
            var v = (VariableTree) t;
            return v.getName().toString();
        } else {
            LOG.warning("Don't know how to create SymbolInformation from " + t);
            return "???";
        }
    }

    @Override
    public List<CodeLens> codeLens(CodeLensParams params) {
        // TODO just create a blank code lens on every method, then resolve it async
        var uri = params.textDocument.uri;
        if (!isJavaFile(uri)) return List.of();
        updateCachedParse(uri);
        var declarations = cacheParse.declarations();
        var result = new ArrayList<CodeLens>();
        for (var d : declarations) {
            var range = cacheParse.range(d);
            if (!range.isPresent()) continue;
            var className = JavaCompilerService.className(d);
            var memberName = JavaCompilerService.memberName(d);
            // If test class or method, add "Run Test" code lens
            if (cacheParse.isTestClass(d)) {
                var arguments = new JsonArray();
                arguments.add(uri.toString());
                arguments.add(className);
                arguments.add(JsonNull.INSTANCE);
                var command = new Command("Run All Tests", "java.command.test.run", arguments);
                var lens = new CodeLens(range.get(), command, null);
                result.add(lens);
                // TODO run all tests in file
                // TODO run all tests in package
            } else if (cacheParse.isTestMethod(d)) {
                var arguments = new JsonArray();
                arguments.add(uri.toString());
                arguments.add(className);
                if (memberName.isPresent()) arguments.add(memberName.get());
                else arguments.add(JsonNull.INSTANCE);
                var command = new Command("Run Test", "java.command.test.run", arguments);
                var lens = new CodeLens(range.get(), command, null);
                result.add(lens);
            }
            // Unresolved "_ references" code lens
            var start = range.get().start;
            var line = start.line;
            var character = start.character;
            var data = new JsonArray();
            data.add("java.command.findReferences");
            data.add(uri.toString());
            data.add(line);
            data.add(character);
            data.add(new Ptr(d).toString());
            var lens = new CodeLens(range.get(), null, data);
            result.add(lens);
        }
        return result;
    }

    private Map<Ptr, Integer> cacheCountReferences = Collections.emptyMap();
    private URI cacheCountReferencesFile = URI.create("file:///NONE");

    private void updateCacheCountReferences(URI current) {
        if (cacheCountReferencesFile.equals(current)) return;
        LOG.info(String.format("Update cached reference count to %s...", current));
        var content = contents(current).content;
        try (var progress = new Progress()) {
            cacheCountReferences = compiler.countReferences(current, content, progress);
        }
        cacheCountReferencesFile = current;
    }

    @Override
    public CodeLens resolveCodeLens(CodeLens unresolved) {
        // TODO This is pretty klugey, should happen asynchronously after CodeLenses are shown
        if (!recentlyOpened.isEmpty()) {
            lint(recentlyOpened);
            recentlyOpened.clear();
        }
        // Unpack data
        var data = unresolved.data;
        var command = data.get(0).getAsString();
        assert command.equals("java.command.findReferences");
        var uriString = data.get(1).getAsString();
        var line = data.get(2).getAsInt();
        var character = data.get(3).getAsInt();
        var ptrString = data.get(4).getAsString();
        // Parse data
        var uri = URI.create(uriString);
        var ptr = new Ptr(ptrString);
        // Update cache if necessary
        updateCacheCountReferences(uri);
        // Read reference count from cache
        var count = cacheCountReferences.getOrDefault(ptr, 0);
        // Update command
        String title;
        if (count == 1) title = "1 reference";
        else title = String.format("%d references", count);
        var arguments = new JsonArray();
        arguments.add(uri.toString());
        arguments.add(line);
        arguments.add(character);
        unresolved.command = new Command(title, command, arguments);

        return unresolved;
    }

    private List<TextEdit> fixImports(URI java) {
        var contents = contents(java).content;
        var fix = compiler.compileFile(java, contents).fixImports();
        // TODO if imports already match fixed-imports, return empty list
        // TODO preserve comments and other details of existing imports
        var edits = new ArrayList<TextEdit>();
        // Delete all existing imports
        for (var i : fix.parsed.getImports()) {
            if (!i.isStatic()) {
                var offset = fix.sourcePositions.getStartPosition(fix.parsed, i);
                var line = (int) fix.parsed.getLineMap().getLineNumber(offset) - 1;
                var delete = new TextEdit(new Range(new Position(line, 0), new Position(line + 1, 0)), "");
                edits.add(delete);
            }
        }
        if (fix.fixedImports.isEmpty()) return edits;
        // Find a place to insert the new imports
        long insertLine = -1;
        var insertText = new StringBuilder();
        // If there are imports, use the start of the first import as the insert position
        for (var i : fix.parsed.getImports()) {
            if (!i.isStatic() && insertLine == -1) {
                long offset = fix.sourcePositions.getStartPosition(fix.parsed, i);
                insertLine = fix.parsed.getLineMap().getLineNumber(offset) - 1;
            }
        }
        // If there are no imports, insert after the package declaration
        if (insertLine == -1 && fix.parsed.getPackageName() != null) {
            long offset = fix.sourcePositions.getEndPosition(fix.parsed, fix.parsed.getPackageName());
            insertLine = fix.parsed.getLineMap().getLineNumber(offset);
            insertText.append("\n");
        }
        // If there are no imports and no package, insert at the top of the file
        if (insertLine == -1) {
            insertLine = 0;
        }
        // Insert each import
        fix.fixedImports
                .stream()
                .sorted()
                .forEach(
                        i -> {
                            insertText.append("import ").append(i).append(";\n");
                        });
        var insertPosition = new Position((int) insertLine, 0);
        var insert = new TextEdit(new Range(insertPosition, insertPosition), insertText.toString());
        edits.add(insert);
        return edits;
    }

    @Override
    public List<TextEdit> formatting(DocumentFormattingParams params) {
        var uri = params.textDocument.uri;
        return fixImports(uri);
    }

    @Override
    public WorkspaceEdit rename(RenameParams params) {
        return null; // TODO
    }

    private boolean isJavaFile(URI uri) {
        return uri.getScheme().equals("file") && uri.getPath().endsWith(".java");
    }

    private List<URI> recentlyOpened = new ArrayList<>();

    @Override
    public void didOpenTextDocument(DidOpenTextDocumentParams params) {
        var document = params.textDocument;
        var uri = document.uri;
        if (!isJavaFile(uri)) return;
        LOG.info(String.format("Opened %s", Parser.fileName(uri)));
        activeDocuments.put(uri, new VersionedContent(document.text, document.version));
        recentlyOpened.add(uri);
        updateCachedParse(uri); // So that subsequent documentSymbol and codeLens requests will be faster
    }

    @Override
    public void didChangeTextDocument(DidChangeTextDocumentParams params) {
        var document = params.textDocument;
        var uri = document.uri;
        if (isJavaFile(uri)) {
            var existing = activeDocuments.get(uri);
            var newText = existing.content;

            if (document.version > existing.version) {
                for (var change : params.contentChanges) {
                    if (change.range == null) newText = change.text;
                    else newText = patch(newText, change);
                }

                activeDocuments.put(uri, new VersionedContent(newText, document.version));
            } else LOG.warning("Ignored change with version " + document.version + " <= " + existing.version);
        }
    }

    private String patch(String sourceText, TextDocumentContentChangeEvent change) {
        try {
            var range = change.range;
            var reader = new BufferedReader(new StringReader(sourceText));
            var writer = new StringWriter();

            // Skip unchanged lines
            int line = 0;

            while (line < range.start.line) {
                writer.write(reader.readLine() + '\n');
                line++;
            }

            // Skip unchanged chars
            for (int character = 0; character < range.start.character; character++) writer.write(reader.read());

            // Write replacement text
            writer.write(change.text);

            // Skip replaced text
            reader.skip(change.rangeLength);

            // Write remaining text
            while (true) {
                int next = reader.read();

                if (next == -1) return writer.toString();
                else writer.write(next);
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void didCloseTextDocument(DidCloseTextDocumentParams params) {
        var document = params.textDocument;
        var uri = document.uri;
        if (isJavaFile(uri)) {
            // Remove from source cache
            activeDocuments.remove(uri);

            // Clear diagnostics
            publishDiagnostics(Collections.singletonList(uri), List.of());
        }
    }

    @Override
    public void didSaveTextDocument(DidSaveTextDocumentParams params) {
        var uri = params.textDocument.uri;
        if (isJavaFile(uri)) {
            // Re-lint all active documents
            lint(activeDocuments.keySet());
            // TODO update config when java file implies a new source root
        }
        // TODO update config when pom.xml changes
    }

    Set<URI> activeDocuments() {
        return activeDocuments.keySet();
    }

    VersionedContent contents(URI openFile) {
        if (!isJavaFile(openFile)) {
            LOG.warning("Ignoring non-java file " + openFile);
            return VersionedContent.EMPTY;
        }
        if (activeDocuments.containsKey(openFile)) {
            return activeDocuments.get(openFile);
        }
        try {
            var content = Files.readAllLines(Paths.get(openFile)).stream().collect(Collectors.joining("\n"));
            return new VersionedContent(content, -1);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
