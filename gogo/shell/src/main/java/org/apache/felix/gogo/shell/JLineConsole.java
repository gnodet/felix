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
package org.apache.felix.gogo.shell;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.lang.reflect.Array;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.felix.gogo.options.Options;
import org.apache.felix.gogo.runtime.Closure;
import org.apache.felix.gogo.runtime.CommandProcessorImpl;
import org.apache.felix.gogo.runtime.CommandSessionImpl;
import org.apache.felix.gogo.runtime.EOFError;
import org.apache.felix.gogo.runtime.Expander;
import org.apache.felix.gogo.runtime.Parser.Program;
import org.apache.felix.gogo.runtime.Parser.Statement;
import org.apache.felix.gogo.runtime.SyntaxError;
import org.apache.felix.gogo.runtime.Token;
import org.apache.felix.gogo.shell.Main;
import org.apache.felix.gogo.shell.Posix;
import org.apache.felix.gogo.shell.Shell;
import org.apache.felix.service.command.CommandProcessor;
import org.apache.felix.service.command.CommandSession;
import org.apache.felix.service.command.Converter;
import org.apache.felix.service.command.Function;
import org.jline.Candidate;
import org.jline.Console;
import org.jline.ConsoleReader;
import org.jline.ConsoleReader.Option;
import org.jline.History;
import org.jline.JLine;
import org.jline.console.Attributes;
import org.jline.editor.Less;
import org.jline.editor.Less.PathSource;
import org.jline.editor.Less.Source;
import org.jline.editor.Less.StdInSource;
import org.jline.editor.Nano;
import org.jline.keymap.Binding;
import org.jline.keymap.KeyMap;
import org.jline.keymap.Macro;
import org.jline.keymap.Reference;
import org.jline.keymap.Widget;
import org.jline.reader.ConsoleReaderImpl;
import org.jline.reader.DefaultHighlighter;
import org.jline.reader.EndOfFileException;
import org.jline.reader.ParsedLine;
import org.jline.reader.UserInterruptException;
import org.jline.reader.completer.FileNameCompleter;
import org.jline.reader.history.FileHistory;
import org.jline.utils.Ansi;
import org.jline.utils.Ansi.Color;
import org.jline.utils.InfoCmp.Capability;

public class JLineConsole implements Runnable {

    public static final String COMPLETION_DESCRIPTION = "description";
    public static final String COMPLETION_SHORT_OPTION = "short-option";
    public static final String COMPLETION_LONG_OPTION = "long-option";
    public static final String COMPLETION_ARGUMENT = "argument";
    public static final String COMPLETION_CONDITION = "condition";
    public static final String COMPLETION_OPTIONS = "options";

    public static final String VAR_COMPLETIONS = ".completions";
    public static final String VAR_COMMAND_LINE = ".commandLine";
    public static final String VAR_READER = ".reader";
    public static final String VAR_SESSION = ".session";
    public static final String VAR_PROCESSOR = ".processor";
    public static final String VAR_CONSOLE = ".console";
    public static final String VAR_EXCEPTION = "exception";
    public static final String VAR_LOCATION = ".location";
    public static final String VAR_PROMPT = "prompt";
    public static final String VAR_RPROMPT = "rprompt";
    public static final String VAR_SCOPE = "SCOPE";

    CommandSession session;
    org.jline.Console console;
    ConsoleReaderImpl reader;

    public JLineConsole(CommandProcessor processor, CommandSession session) throws IOException {
        this.session = session;
        this.console = JLine.builder()
                            .appName("gogo")
                            .variables(((CommandSessionImpl) session).getVariables())
                            .completer(new Completer(session))
                            .highlighter(new Highlighter(session))
                            .history(new FileHistory(new File(System.getProperty("user.home"), ".gogo.history")))
                            .parser(new Parser())
                            .build();
        this.reader = (ConsoleReaderImpl) console.newConsoleReader();
        this.session.put(VAR_CONSOLE, console);
        this.session.put(VAR_READER, reader);
        this.session.put(VAR_PROCESSOR, processor);
        this.session.put(VAR_SESSION, session);
        Main.register((CommandProcessorImpl) processor,
                new JLineCommands(),
                JLineCommands.functions);
        try {
            session.execute(Shell.readScript(getClass().getResource("/gosh_completion").toURI()));
        } catch (Exception e) {
            this.console.writer().println(e.getMessage());
        }
    }

    public static Console getConsole(CommandSession session) {
        return (Console) session.get(VAR_CONSOLE);
    }

    public static ConsoleReaderImpl getReader(CommandSession session) {
        return (ConsoleReaderImpl) session.get(VAR_READER);
    }

    public static CommandProcessor getProcessor(CommandSession session) {
        return (CommandProcessor) session.get(VAR_PROCESSOR);
    }
    
    @SuppressWarnings("unchecked")
    public static Set<String> getCommands(CommandSession session) {
        return (Set<String>) session.get(CommandSessionImpl.COMMANDS);
    }

    public static ParsedLine getParsedLine(CommandSession session) {
        return (ParsedLine) session.get(VAR_COMMAND_LINE);
    }

    public void run() {
        while (true) {
            try {
                reader.readLine(getPrompt(), getRPrompt(), null, null);
                ParsedLine parsedLine = reader.getParsedLine();
                if (parsedLine == null) {
                    continue;
                }
                try {
                    Object result = session.execute(((ParsedLineImpl) parsedLine).program());
                    session.put("_", result); // set $_ to last result

                    if (result != null && !Boolean.FALSE.equals(session.get(".Gogo.format"))) {
                        System.out.println(session.format(result, Converter.INSPECT));
                    }
                } catch (Exception e) {
                    session.put(VAR_EXCEPTION, e);
                    Object loc = session.get(VAR_LOCATION);

                    if (null == loc || !loc.toString().contains(":"))
                    {
                        loc = "gogo";
                    }
                    System.out.println(loc + ": " + e.getClass().getSimpleName() + ": "
                            + e.getMessage());
                }

            } catch (UserInterruptException e) {
                // continue;
            } catch (EndOfFileException e) {
                try {
                    reader.getHistory().flush();
                } catch (IOException e1) {
                    e.addSuppressed(e1);
                }
                break;
            }
        }
    }

    private String getPrompt() {
        return expand(VAR_PROMPT, "gl! ");
    }

    private String getRPrompt() {
        return expand(VAR_RPROMPT, null);
    }

    // Simple expansion, we should use a closure
    private String expand(String name, String def) {
        Object prompt = session.get(name);
        if (prompt != null) {
            try {
                Object o = Expander.expand(
                                prompt.toString(),
                                new Closure((CommandSessionImpl) session, null, null));
                if (o != null) {
                    return o.toString();
                }
            } catch (Exception e) {
                // ignore
            }
        }
        return def;
   }

    public static class Highlighter extends DefaultHighlighter {
        private final CommandSession session;

        public Highlighter(CommandSession session) {
            this.session = session;
        }

        enum Type {
            Reserved,
            String,
            Number,
            Variable,
            VariableName,
            Function,
            BadFunction,
            Value,
            Constant,
            Unknown
        }

        public String highlight(ConsoleReader reader, String buffer) {
            try {
                Program program = null;
                List<Token> tokens = null;
                List<Statement> statements = null;
                String repaired = buffer + " ";
                while (program == null) {
                    try {
                        org.apache.felix.gogo.runtime.Parser parser = new org.apache.felix.gogo.runtime.Parser(repaired);
                        program = parser.program();
                        tokens = parser.tokens();
                        statements = parser.statements();
                    } catch (EOFError e) {
                        repaired = repaired + e.repair();
                    }
                }

                Ansi ansi = Ansi.ansi();

                int underlineStart = -1;
                int underlineEnd = -1;
                if (reader instanceof ConsoleReaderImpl) {
                    String search = ((ConsoleReaderImpl) reader).getSearchTerm();
                    if (search != null && search.length() > 0) {
                        underlineStart = buffer.indexOf(search);
                        if (underlineStart >= 0) {
                            underlineEnd = underlineStart + search.length() - 1;
                        }
                    }
                }

                int cur = 0;
                for (Token token : tokens) {
                    // We're on the repair side, so exit now
                    if (token.start() >= buffer.length()) {
                        break;
                    }
                    if (token.start() > cur) {
                        ansi.a(buffer.substring(cur, token.start()));
                        cur = token.start();
                    }
                    // Find corresponding statement
                    Statement statement = null;
                    for (int i = statements.size() - 1; i >= 0; i--) {
                        Statement s = statements.get(i);
                        if (s.start() <= cur && cur < s.start() + s.length()) {
                            statement = s;
                            break;
                        }
                    }

                    // Reserved tokens
                    Type type = Type.Unknown;
                    if (Token.eq(token, "{")
                            || Token.eq(token, "}")
                            || Token.eq(token, "(")
                            || Token.eq(token, ")")
                            || Token.eq(token, "[")
                            || Token.eq(token, "]")
                            || Token.eq(token, "|")
                            || Token.eq(token, ";")
                            || Token.eq(token, "=")) {
                        type = Type.Reserved;
                    } else if (token.charAt(0) == '\'' || token.charAt(0) == '"') {
                        type = Type.String;
                    } else if (token.toString().matches("^[-+]?[0-9]*\\.?[0-9]+([eE][-+]?[0-9]+)?$")) {
                        type = Type.Number;
                    } else if (token.charAt(0) == '$') {
                        type = Type.Variable;
                    } else if (((Set) session.get(CommandSessionImpl.CONSTANTS)).contains(token.toString())
                            || Token.eq(token, "null") || Token.eq(token, "false") || Token.eq(token, "true")) {
                        type = Type.Constant;
                    } else {
                        boolean isFirst = statement != null && statement.tokens().size() > 0
                                && token == statement.tokens().get(0);
                        boolean isThirdWithNext = statement != null && statement.tokens().size() > 3
                                && token == statement.tokens().get(2);
                        boolean isAssign = statement != null && statement.tokens().size() > 1
                                && Token.eq(statement.tokens().get(1), "=");
                        if (isFirst && isAssign) {
                            type = Type.VariableName;
                        }
                        if (isFirst && !isAssign || isAssign && isThirdWithNext) {
                            Object v = session.get(resolve(token.toString()));
                            type = (v instanceof Function) ? Type.Function : Type.BadFunction;
                        }
                    }
                    String valid;
                    if (token.start() + token.length() <= buffer.length()) {
                        valid = token.toString();
                    } else {
                        valid = token.subSequence(0, buffer.length() - token.start()).toString();
                    }
                    switch (type) {
                        case Reserved:
                            ansi.fg(Color.MAGENTA).a(valid).fg(Color.DEFAULT);
                            break;
                        case String:
                        case Number:
                        case Constant:
                            ansi.fg(Color.GREEN).a(valid).fg(Color.DEFAULT);
                            break;
                        case Variable:
                        case VariableName:
                            ansi.fg(Color.CYAN).a(valid).fg(Color.DEFAULT);
                            break;
                        case Function:
                            ansi.fg(Color.BLUE).a(valid).fg(Color.DEFAULT);
                            break;
                        case BadFunction:
                            ansi.fg(Color.RED).a(valid).fg(Color.DEFAULT);
                            break;
                        default:
                            ansi.a(valid);
                            break;
                    }
                    cur = token.start() + valid.length();
                }
                if (cur < buffer.length()) {
                    ansi.a(buffer.substring(cur));
                }
                if (buffer.length() < repaired.length()) {
                    ansi.fgBright(Color.BLACK);
                    ansi.a(repaired.substring(buffer.length()));
                    ansi.fg(Color.DEFAULT);
                }
                return ansi.toString();
            } catch (SyntaxError e) {
                return super.highlight(reader, buffer);
            }
        }

        private String resolve(String command) {
            String resolved = command;
            if (command.indexOf(':') < 0) {
                Set<String> commands = getCommands(session);
                Object path = session.get(VAR_SCOPE);
                String scopePath = (null == path ? "*" : path.toString());
                for (String scope : scopePath.split(":")) {
                    for (String entry : commands) {
                        if ("*".equals(scope) && entry.endsWith(":" + command)
                                || entry.equals(scope + ":" + command)) {
                            resolved = entry;
                            break;
                        }
                    }
                }
            }
            return resolved;
        }
    }

    public static class Completer implements org.jline.Completer {

        private final CommandSession session;

        public Completer(CommandSession session) {
            this.session = session;
        }

        public void complete(ConsoleReader reader, ParsedLine line, List<Candidate> candidates) {
            if (line.wordIndex() == 0) {
                completeCommand(candidates);
            } else {
                tryCompleteArguments(line, candidates);
            }
        }

        @SuppressWarnings("unchecked")
        protected void tryCompleteArguments(ParsedLine line, List<Candidate> candidates) {
            String command = line.words().get(0);
            String resolved = resolve(command);
            Object o = session.get(VAR_COMPLETIONS);
            if (o instanceof Map) {
                o = ((Map) o).get(resolved);
                if (o instanceof List) {
                    List<Map<String, Object>> completions = (List) o;
                    completeCommandArguments(line, candidates, completions);
                }
            }
        }

        @SuppressWarnings("unchecked")
        protected void completeCommandArguments(ParsedLine line, List<Candidate> candidates, List<Map<String, Object>> completions) {
            session.put(VAR_COMMAND_LINE, line);
            for (Map<String, Object> completion : completions) {
                boolean isOption = line.word().startsWith("-");
                String prevOption = line.wordIndex() >= 2 && line.words().get(line.wordIndex() - 1).startsWith("-")
                        ? line.words().get(line.wordIndex() - 1) : null;
                List<String> options = (List<String>) completion.get(COMPLETION_OPTIONS);
                String argument = (String) completion.get(COMPLETION_ARGUMENT);
                String condition = (String) completion.get(COMPLETION_CONDITION);
                String description = (String) completion.get(COMPLETION_DESCRIPTION);
                String key = UUID.randomUUID().toString();
                boolean conditionValue = true;
                if (condition != null) {
                    Object res = Boolean.FALSE;
                    try {
                        res = session.execute(condition);
                    } catch (Throwable t) {
                        t.getCause();
                        // Ignore
                    }
                    conditionValue = isTrue(res);
                }
                if (conditionValue && isOption && options != null) {
                    for (String opt : options) {
                        candidates.add(new Candidate(opt, opt, "options", description, null, key, true));
                    }
                } else if (!isOption && prevOption != null && argument != null
                        && (options != null && options.contains(prevOption))) {
                    Object res = null;
                    try {
                        res = session.execute(argument);
                    } catch (Throwable t) {
                        // Ignore
                    }
                    if (res instanceof Candidate) {
                        candidates.add((Candidate) res);
                    } else if (res instanceof String) {
                        candidates.add(new Candidate((String) res, (String) res, null, null, null, null, true));
                    } else if (res instanceof Collection) {
                        for (Object s : (Collection) res) {
                            if (s instanceof Candidate) {
                                candidates.add((Candidate) s);
                            } else if (s instanceof String) {
                                candidates.add(new Candidate((String) s, (String) s, null, null, null, null, true));
                            }
                        }
                    } else if (res != null && res.getClass().isArray()) {
                        for (int i = 0, l = Array.getLength(res); i < l; i++) {
                            Object s = Array.get(res, i);
                            if (s instanceof Candidate) {
                                candidates.add((Candidate) s);
                            } else if (s instanceof String) {
                                candidates.add(new Candidate((String) s, (String) s, null, null, null, null, true));
                            }
                        }
                    }
                } else if (!isOption && argument != null) {
                    Object res = null;
                    try {
                        res = session.execute(argument);
                    } catch (Throwable t) {
                        // Ignore
                    }
                    if (res instanceof Candidate) {
                        candidates.add((Candidate) res);
                    } else if (res instanceof String) {
                        candidates.add(new Candidate((String) res, (String) res, null, description, null, null, true));
                    } else if (res instanceof Collection) {
                        for (Object s : (Collection) res) {
                            if (s instanceof Candidate) {
                                candidates.add((Candidate) s);
                            } else if (s instanceof String) {
                                candidates.add(new Candidate((String) s, (String) s, null, description, null, null, true));
                            }
                        }
                    }
                }
            }
        }

        @SuppressWarnings("unchecked")
        protected void completeCommand(List<Candidate> candidates) {
            Set<String> commands = getCommands(session);
            for (String command : commands) {
                String name = command.substring(command.indexOf(':') + 1);
                boolean resolved = command.equals(resolve(name));
                if (!name.startsWith("_")) {
                    String desc = null;
                    Object o = session.get(VAR_COMPLETIONS);
                    if (o instanceof Map) {
                        o = ((Map) o).get(command);
                        if (o instanceof List) {
                            List<Map<String, String>> completions = (List) o;
                            for (Map<String, String> completion : completions) {
                                if (completion.containsKey(COMPLETION_DESCRIPTION)
                                        && !completion.containsKey(COMPLETION_SHORT_OPTION)
                                        && !completion.containsKey(COMPLETION_LONG_OPTION)
                                        && !completion.containsKey(COMPLETION_CONDITION)
                                        && !completion.containsKey(COMPLETION_ARGUMENT)) {
                                    desc = completion.get(COMPLETION_DESCRIPTION);
                                }
                            }
                        }
                    }
                    String key = UUID.randomUUID().toString();
                    if (desc != null) {
                        candidates.add(new Candidate(command, command, null, desc, null, key, true));
                        if (resolved) {
                            candidates.add(new Candidate(name, name, null, desc, null, key, true));
                        }
                    } else {
                        candidates.add(new Candidate(command, command, null, null, null, key, true));
                        if (resolved) {
                            candidates.add(new Candidate(name, name, null, null, null, key, true));
                        }
                    }
                }
            }
        }

        private String resolve(String command) {
            String resolved = command;
            if (command.indexOf(':') < 0) {
                Set<String> commands = getCommands(session);
                Object path = session.get(VAR_SCOPE);
                String scopePath = (null == path ? "*" : path.toString());
                for (String scope : scopePath.split(":")) {
                    for (String entry : commands) {
                        if ("*".equals(scope) && entry.endsWith(":" + command)
                                || entry.equals(scope + ":" + command)) {
                            resolved = entry;
                            break;
                        }
                    }
                }
            }
            return resolved;
        }

        private boolean isTrue(Object result) {
            if (result == null)
                return false;
            if (result instanceof Boolean)
                return (Boolean) result;
            if (result instanceof Number) {
                if (0 == ((Number) result).intValue())
                    return false;
            }
            if ("".equals(result) || "0".equals(result))
                return false;

            return true;
        }

    }

    public static class DirectoriesCompleter extends FileNameCompleter {

        private final CommandSession session;

        public DirectoriesCompleter(CommandSession session) {
            this.session = session;
        }

        @Override
        protected Path getUserDir() {
            return Posix._pwd(session).toPath();
        }

        @Override
        protected boolean accept(Path path) {
            return java.nio.file.Files.isDirectory(path);
        }
    }

    public static class FilesCompleter extends org.jline.reader.completer.FileNameCompleter {

        private final CommandSession session;

        public FilesCompleter(CommandSession session) {
            this.session = session;
        }

        @Override
        protected Path getUserDir() {
            return Posix._pwd(session).toPath();
        }
    }

    public static class Parser implements org.jline.reader.Parser {

        public ParsedLine parse(String line, int cursor) throws org.jline.reader.SyntaxError {
            try {
                return doParse(line, cursor);
            }
            catch (EOFError e)
            {
                throw new org.jline.reader.EOFError(e.line(), e.column(), e.getMessage(), e.missing());
            } catch (SyntaxError e) {
                throw new org.jline.reader.SyntaxError(e.line(), e.column(), e.getMessage());
            }
        }

        private ParsedLine doParse(CharSequence line, int cursor)  throws SyntaxError
        {
            org.apache.felix.gogo.runtime.Parser parser = new org.apache.felix.gogo.runtime.Parser(line);
            Program program = parser.program();
            List<Statement>statements = parser.statements();
            // Find corresponding statement
            Statement statement = null;
            for (int i = statements.size() - 1; i >= 0; i--) {
                Statement s = statements.get(i);
                if (s.start() <= cursor) {
                    boolean isOk = true;
                    // check if there are only spaces after the previous statement
                    if (s.start() + s.length() < cursor) {
                        for (int j = s.start() + s.length(); isOk && j < cursor; j++)
                        {
                            isOk = Character.isWhitespace(line.charAt(j));
                        }
                    }
                    statement = s;
                    break;
                }
            }
            if (statement != null)
            {
                return new ParsedLineImpl(program, statement, cursor, statement.tokens());
            }
            else
            {
                // TODO:
                return new ParsedLineImpl(program, program, cursor, Collections.<Token>singletonList(program));
            }
        }

    }

    public static class ParsedLineImpl implements ParsedLine {

        private final Program program;
        private final String source;
        private final int cursor;
        private final List<String> tokens;
        private final int wordIndex;
        private final int wordCursor;

        public ParsedLineImpl(Program program, Token line, int cursor, List<Token> tokens) {
            this.program = program;
            this.source = line.toString();
            this.cursor = cursor - line.start();
            this.tokens = new ArrayList<String>();
            for (Token token : tokens) {
                this.tokens.add(token.toString());
            }
            int wi = tokens.size();
            int wc = 0;
            if (cursor >= 0) {
                for (int i = 0; i < tokens.size(); i++) {
                    Token t = tokens.get(i);
                    if (t.start() > cursor) {
                        wi = i;
                        wc = 0;
                        this.tokens.add(i, "");
                        break;
                    }
                    if (t.start() + t.length() >= cursor) {
                        wi = i;
                        wc = cursor - t.start();
                        break;
                    }
                }
            }
            if (wi == tokens.size()) {
                this.tokens.add("");
            }
            wordIndex = wi;
            wordCursor = wc;
        }

        public String word() {
            return tokens.get(wordIndex());
        }

        public int wordCursor() {
            return wordCursor;
        }

        public int wordIndex() {
            return wordIndex;
        }

        public List<String> words() {
            return tokens;
        }

        public String line() {
            return source;
        }

        public int cursor() {
            return cursor;
        }

        public Program program() {
            return program;
        }
    }

    public static class JLineCommands {

        public static final String[] functions = {
                "keymap", "setopt", "unsetopt", "complete", "history",
                "less", "watch", "nano", "widget",
                "__files", "__directories", "__usage_completion"
        };

        public void nano(final CommandSession session, String[] argv) throws Exception {
            final String[] usage = {
                    "nano - edit a file",
                    "Usage: nano [FILES]",
                    "  -? --help                    Show help",
            };
            final org.apache.felix.gogo.options.Option opt = Options.compile(usage).parse(argv);
            if (opt.isSet("help")) {
                opt.usage();
                return;
            }
            Console console = getConsole(session);
            Nano edit = new Nano(console, new File(Shell.cwd(session).toURL().toURI()));
            edit.open(opt.args());
            edit.run();
        }

        public void watch(final CommandSession session, String[] argv) throws IOException, InterruptedException {
            final String[] usage = {
                    "watch - watches & refreshes the output of a command",
                    "Usage: watch [OPTIONS] COMMAND",
                    "  -? --help                    Show help",
                    "  -n --interval                Interval between executions of the command in seconds",
                    "  -a --append                  The output should be appended but not clear the console"
            };
            final org.apache.felix.gogo.options.Option opt = Options.compile(usage).parse(argv);
            if (opt.isSet("help")) {
                opt.usage();
                return;
            }
            List<String> args = opt.args();
            if (args.isEmpty()) {
                System.err.println("Argument expected");
                return;
            }
            ScheduledExecutorService executorService = Executors.newSingleThreadScheduledExecutor();
            final Console console = getConsole(session);
            final CommandProcessor processor = getProcessor(session);
            try {
                int interval = 1;
                if (opt.isSet("interval")) {
                    interval = opt.getNumber("interval");
                    if (interval < 1) {
                        interval = 1;
                    }
                }
                final String cmd = join(" ", args);
                Runnable task = new Runnable() {
                    public void run() {
                        ByteArrayOutputStream baos = new ByteArrayOutputStream();
                        InputStream is = new ByteArrayInputStream(new byte[0]);
                        PrintStream os = new PrintStream(baos);
                        if (opt.isSet("append") || !console.puts(Capability.clear_screen)) {
                            console.writer().println();
                        }
                        try {
                            CommandSession ns = processor.createSession(is, os, os);
                            Set<String> vars = getCommands(session);
                            for (String n : vars) {
                                ns.put(n, session.get(n));
                            }
                            ns.execute(cmd);
                        } catch (Throwable t) {
                            t.printStackTrace(os);
                        }
                        os.flush();
                        console.writer().print(baos.toString());
                        console.writer().flush();
                    }
                };
                executorService.scheduleAtFixedRate(task, 0, interval, TimeUnit.SECONDS);
                Attributes attr = console.enterRawMode();
                console.reader().read();
                console.setAttributes(attr);
            } finally {
                executorService.shutdownNow();
            }
        }

        protected String join(CharSequence join, List<String> args) {
            StringBuilder sb = new StringBuilder();
            for (String arg : args) {
                if (sb.length() > 0) {
                    sb.append(join);
                }
                sb.append(arg);
            }
            return sb.toString();
        }

        public void less(CommandSession session, String[] argv) throws IOException, InterruptedException {
            final String[] usage = {
                    "less -  file pager",
                    "Usage: less [OPTIONS] [FILES]",
                    "  -? --help                    Show help",
                    "  -e --quit-at-eof             Exit on second EOF",
                    "  -E --QUIT-AT-EOF             Exit on EOF",
                    "  -q --quiet --silent          Silent mode",
                    "  -Q --QUIET --SILENT          Completely  silent",
                    "  -S --chop-long-lines         Do not fold long lines",
                    "  -i --ignore-case             Search ignores lowercase case",
                    "  -I --IGNORE-CASE             Search ignores all case",
                    "  -x --tabs                    Set tab stops",
                    "  -N --LINE-NUMBERS            Display line number for each line"
            };

            org.apache.felix.gogo.options.Option opt = Options.compile(usage).parse(argv);

            if (opt.isSet("help")) {
                opt.usage();
                return;
            }

            Less less = new Less(getConsole(session));
            less.quitAtFirstEof = opt.isSet("QUIT-AT-EOF");
            less.quitAtSecondEof = opt.isSet("quit-at-eof");
            less.quiet = opt.isSet("quiet");
            less.veryQuiet = opt.isSet("QUIET");
            less.chopLongLines = opt.isSet("chop-long-lines");
            less.ignoreCaseAlways = opt.isSet("IGNORE-CASE");
            less.ignoreCaseCond = opt.isSet("ignore-case");
            if (opt.isSet("tabs")) {
                less.tabs = opt.getNumber("tabs");
            }
            less.printLineNumbers = opt.isSet("LINE-NUMBERS");
            List<Source> sources = new ArrayList<Source>();
            if (opt.args().isEmpty()) {
                opt.args().add("-");
            }
            for (String arg : opt.args()) {
                if ("-".equals(arg)) {
                    sources.add(new StdInSource());
                } else {
                    URI uri = Shell.cwd(session).resolve(arg);
                    sources.add(new PathSource(new File(uri), arg));
                }
            }
            less.run(sources);
        }

        public void history(CommandSession session, String[] argv) throws IOException {
            final String[] usage = {
                    "history -  list history of commands",
                    "Usage: history [OPTIONS]",
                    "  -? --help                       Displays command help",
                    "     --clear                      Clear history",
                    "     --save                       Save history"};

            org.apache.felix.gogo.options.Option opt = Options.compile(usage).parse(argv);

            if (opt.isSet("help")) {
                opt.usage();
                return;
            }
            if (!opt.args().isEmpty()) {
                System.err.println("usage: history [OPTIONS]");
                return;
            }

            ConsoleReaderImpl reader = getReader(session);
            History history = reader.getHistory();
            if (opt.isSet("clear")) {
                history.clear();
            }
            if (opt.isSet("save")) {
                history.flush();
            }
            if (opt.isSet("clear") || opt.isSet("save")) {
                return;
            }
            for (int index = history.first(); index <= history.last(); index++) {
                System.out.println(
                        Ansi.ansi()
                                .a("  ")
                                .bold(String.format("%3d", index))
                                .a("  ")
                                .a(history.get(index))
                                .toString());
            }
        }

        @SuppressWarnings("unchecked")
        public void complete(CommandSession session, String[] argv) {
            final String[] usage = {
                    "complete -  edit command specific tab-completions",
                    "Usage: complete",
                    "  -? --help                       Displays command help",
                    "  -c --command=COMMAND            Command to add completion to",
                    "  -d --description=DESCRIPTION    Description of this completions",
                    "  -e --erase                      Erase the completions",
                    "  -s --short-option=SHORT_OPTION  Posix-style option to complete",
                    "  -l --long-option=LONG_OPTION    GNU-style option to complete",
                    "  -a --argument=ARGUMENTS         A list of possible arguments",
                    "  -n --condition=CONDITION        The completion should only be used if the",
                    "                                  specified command has a zero exit status"};

            org.apache.felix.gogo.options.Option opt = Options.compile(usage).parse(argv);

            if (opt.isSet("help")) {
                opt.usage();
                return;
            }

            String command = opt.get("command");

            Object o = session.get(VAR_COMPLETIONS);
            if (!(o instanceof Map)) {
                o = new HashMap();
                session.put(VAR_COMPLETIONS, o);
            }
            Map<String, Object> completions = (Map) o;

            if (opt.isSet("erase")) {
                completions.remove(command);
                return;
            }

            o = completions.get(command);
            if (!(o instanceof List)) {
                o = new ArrayList();
                completions.put(command, o);
            }
            List<Map<String, Object>> cmdCompletions = (List) o;
            Map<String, Object> comp = new HashMap<String, Object>();
            for (String name : new String[] {
                    COMPLETION_DESCRIPTION,
                    COMPLETION_ARGUMENT,
                    COMPLETION_CONDITION}) {
                if (opt.isSet(name)) {
                    comp.put(name, opt.getObject(name));
                }
            }
            List<String> options = new ArrayList<String>();
            if (opt.isSet(COMPLETION_SHORT_OPTION)) {
                for (String op : opt.getList(COMPLETION_SHORT_OPTION)) {
                    options.add("-" + op);
                }
            }
            if (opt.isSet(COMPLETION_LONG_OPTION)) {
                for (String op : opt.getList(COMPLETION_LONG_OPTION)) {
                    options.add("--" + op);
                }
            }
            if (!options.isEmpty()) {
                comp.put(COMPLETION_OPTIONS, options);
            }
            cmdCompletions.add(comp);
        }

        public void widget(final CommandSession session, String[] argv) throws Exception {
            final String[] usage = {
                    "widget -  manipulate widgets",
                    "Usage: widget [options] -N new-widget [function-name]",
                    "       widget [options] -D widget ...",
                    "       widget [options] -A old-widget new-widget",
                    "       widget [options] -U string ...",
                    "       widget [options] -l",
                    "  -? --help                       Displays command help",
                    "  -A                              Create alias to widget",
                    "  -N                              Create new widget",
                    "  -D                              Delete widgets",
                    "  -U                              Push characters to the stack",
                    "  -l                              List user-defined widgets",
                    "  -a                              With -l, list all widgets"
            };
            org.apache.felix.gogo.options.Option opt = Options.compile(usage).parse(argv);
            if (opt.isSet("help")) {
                opt.usage();
                return;
            }
            ConsoleReaderImpl reader = getReader(session);

            int actions = (opt.isSet("N") ? 1 : 0)
                    + (opt.isSet("D") ? 1 : 0)
                    + (opt.isSet("U") ? 1 : 0)
                    + (opt.isSet("l") ? 1 : 0)
                    + (opt.isSet("A") ? 1 : 0);
            if (actions > 1) {
                System.err.println("keymap: incompatible operation selection options");
                return;
            }
            if (opt.isSet("l")) {
                Set<String> widgets = new TreeSet<String>(reader.getWidgets().keySet());
                if (!opt.isSet("a")){
                    widgets.removeAll(reader.getBuiltinWidgets().keySet());
                }
                for (String name : widgets) {
                    System.out.println(name);
                }
            }
            else if (opt.isSet("N")) {
                if (opt.args().size() < 1) {
                    System.err.println("widget: not enough arguments for -N");
                    return;
                }
                if (opt.args().size() > 2) {
                    System.err.println("widget: too many arguments for -N");
                    return;
                }
                final String name = opt.args().get(0);
                final String func = opt.args().size() == 2 ? opt.args().get(1) : name;
                reader.getWidgets().put(name, new Widget() {
                    public boolean apply() {
                        try {
                            session.execute(func);
                        } catch (Exception e) {
                            // TODO: log exception ?
                        }
                        return true;
                    }
                });
            } else if (opt.isSet("D")) {
                for (String name : opt.args()) {
                    reader.getWidgets().remove(name);
                }
            } else if (opt.isSet("A")) {
                if (opt.args().size() < 2) {
                    System.err.println("widget: not enough arguments for -A");
                    return;
                }
                if (opt.args().size() > 2) {
                    System.err.println("widget: too many arguments for -A");
                    return;
                }
                Widget org = reader.getWidgets().get(opt.args().get(0));
                if (org == null) {
                    System.err.println("widget: no such widget `" + opt.args().get(0) + "'");
                    return;
                }
                reader.getWidgets().put(opt.args().get(1), org);
            }
            else if (opt.isSet("U")) {
                for (String arg : opt.args()) {
                    reader.runMacro(KeyMap.translate(arg));
                }
            }
            else if (opt.args().size() == 1) {
                reader.callWidget(opt.args().get(0));
            }
        }

        public void keymap(CommandSession session, String[] argv) {
            final String[] usage = {
                    "keymap -  manipulate keymaps",
                    "Usage: keymap [options] -l [-L] [keymap ...]",
                    "       keymap [options] -d",
                    "       keymap [options] -D keymap ...",
                    "       keymap [options] -A old-keymap new-keymap",
                    "       keymap [options] -N new-keymap [old-keymap]",
                    "       keymap [options] -m",
                    "       keymap [options] -r in-string ...",
                    "       keymap [options] -s in-string out-string ...",
                    "       keymap [options] in-string command ...",
                    "       keymap [options] [in-string]",
                    "  -? --help                       Displays command help",
                    "  -A                              Create alias to keymap",
                    "  -D                              Delete named keymaps",
                    "  -L                              Output in form of keymap commands",
                    "  -M (default=main)               Specify keymap to select",
                    "  -N                              Create new keymap",
                    "  -R                              Interpret in-strings as ranges",
                    "  -a                              Select vicmd keymap",
                    "  -d                              Delete existing keymaps and reset to default state",
                    "  -e                              Select emacs keymap and bind it to main",
                    "  -l                              List existing keymap names",
                    "  -p                              List bindings which have given key sequence as a a prefix",
                    "  -r                              Unbind specified in-strings",
                    "  -s                              Bind each in-string to each out-string",
                    "  -v                              Select viins keymap and bind it to main",
                    };
            org.apache.felix.gogo.options.Option opt = Options.compile(usage).parse(argv);
            if (opt.isSet("help")) {
                opt.usage();
                return;
            }

            ConsoleReaderImpl reader = getReader(session);
            Map<String, KeyMap> keyMaps = reader.getKeyMaps();

            int actions = (opt.isSet("N") ? 1 : 0)
                    + (opt.isSet("d") ? 1 : 0)
                    + (opt.isSet("D") ? 1 : 0)
                    + (opt.isSet("l") ? 1 : 0)
                    + (opt.isSet("r") ? 1 : 0)
                    + (opt.isSet("s") ? 1 : 0)
                    + (opt.isSet("A") ? 1 : 0);
            if (actions > 1) {
                System.err.println("keymap: incompatible operation selection options");
                return;
            }
            if (opt.isSet("l")) {
                boolean commands = opt.isSet("L");
                // TODO: handle commands
                if (opt.args().size() > 0) {
                    for (String arg : opt.args()) {
                        KeyMap map = keyMaps.get(arg);
                        if (map == null) {
                            System.err.println("keymap: no such keymap: `" + arg + "'");
                        } else {
                            System.out.println(arg);
                        }
                    }
                } else {
                    for (String name : keyMaps.keySet()) {
                        System.out.println(name);
                    }
                }
            }
            else if (opt.isSet("N")) {
                if (opt.isSet("e") || opt.isSet("v") || opt.isSet("a") || opt.isSet("M")) {
                    System.err.println("keymap: keymap can not be selected with -N");
                    return;
                }
                if (opt.args().size() < 1) {
                    System.err.println("keymap: not enough arguments for -N");
                    return;
                }
                if (opt.args().size() > 2) {
                    System.err.println("keymap: too many arguments for -N");
                    return;
                }
                KeyMap org = null;
                if (opt.args().size() == 2) {
                    org = keyMaps.get(opt.args().get(1));
                    if (org == null) {
                        System.err.println("keymap: no such keymap `" + opt.args().get(1) + "'");
                        return;
                    }
                }
                KeyMap map = new KeyMap();
                if (org != null) {
                    for (Map.Entry<String, Binding> bound : org.getBoundKeys().entrySet()) {
                        map.bind(bound.getValue(), bound.getKey());
                    }
                }
                keyMaps.put(opt.args().get(0), map);
            }
            else if (opt.isSet("A")) {
                if (opt.isSet("e") || opt.isSet("v") || opt.isSet("a") || opt.isSet("M")) {
                    System.err.println("keymap: keymap can not be selected with -N");
                    return;
                }
                if (opt.args().size() < 2) {
                    System.err.println("keymap: not enough arguments for -A");
                    return;
                }
                if (opt.args().size() > 2) {
                    System.err.println("keymap: too many arguments for -A");
                    return;
                }
                KeyMap org = keyMaps.get(opt.args().get(0));
                if (org == null) {
                    System.err.println("keymap: no such keymap `" + opt.args().get(0) + "'");
                    return;
                }
                keyMaps.put(opt.args().get(1), org);
            }
            else if (opt.isSet("d")) {
                if (opt.isSet("e") || opt.isSet("v") || opt.isSet("a") || opt.isSet("M")) {
                    System.err.println("keymap: keymap can not be selected with -N");
                    return;
                }
                if (opt.args().size() > 0) {
                    System.err.println("keymap: too many arguments for -d");
                    return;
                }
                keyMaps.clear();
                keyMaps.putAll(reader.defaultKeyMaps());
            }
            else if (opt.isSet("D")) {
                if (opt.isSet("e") || opt.isSet("v") || opt.isSet("a") || opt.isSet("M")) {
                    System.err.println("keymap: keymap can not be selected with -N");
                    return;
                }
                if (opt.args().size() < 1) {
                    System.err.println("keymap: not enough arguments for -A");
                    return;
                }
                for (String name : opt.args()) {
                    if (keyMaps.remove(name) == null) {
                        System.err.println("keymap: no such keymap `" + name + "'");
                        return;
                    }
                }
            }
            else if (opt.isSet("r")) {
                // Select keymap
                String keyMapName = ConsoleReaderImpl.MAIN;
                int sel = (opt.isSet("a") ? 1 : 0)
                        + (opt.isSet("e") ? 1 : 0)
                        + (opt.isSet("v") ? 1 : 0)
                        + (opt.isSet("M") ? 1 : 0);
                if (sel > 1) {
                    System.err.println("keymap: incompatible keymap selection options");
                    return;
                } else if (opt.isSet("a")) {
                    keyMapName = ConsoleReaderImpl.VICMD;
                } else if (opt.isSet("e")) {
                    keyMapName = ConsoleReaderImpl.EMACS;
                } else if (opt.isSet("v")) {
                    keyMapName = ConsoleReaderImpl.VIINS;
                } else if (opt.isSet("M")) {
                    if (opt.args().isEmpty()) {
                        System.err.println("keymap: argument expected: -M");
                        return;
                    }
                    keyMapName = opt.args().remove(0);
                }
                KeyMap map = keyMaps.get(keyMapName);
                if (map == null) {
                    System.err.println("keymap: no such keymap `" + keyMapName + "'");
                    return;
                }
                // Unbind
                boolean range = opt.isSet("R");
                boolean prefix = opt.isSet("p");
                Set<String> toRemove = new HashSet<String>();
                Map<String, Binding> bound = map.getBoundKeys();
                for (String arg : opt.args()) {
                    if (range) {
                        Collection<String> r = KeyMap.range(opt.args().get(0));
                        if (r == null) {
                            System.err.println("keymap: malformed key range `" + opt.args().get(0) + "'");
                            return;
                        }
                        toRemove.addAll(r);
                    } else {
                        String seq = KeyMap.translate(arg);
                        for (String k : bound.keySet()) {
                            if (prefix && k.startsWith(seq) && k.length() > seq.length()
                                    || !prefix && k.equals(seq)) {
                                toRemove.add(k);
                            }
                        }
                    }
                }
                for (String seq : toRemove) {
                    map.unbind(seq);
                }
                if (opt.isSet("e") || opt.isSet("v")) {
                    keyMaps.put(ConsoleReaderImpl.MAIN, map);
                }
            }
            else if (opt.isSet("s") || opt.args().size() > 1) {
                // Select keymap
                String keyMapName = ConsoleReaderImpl.MAIN;
                int sel = (opt.isSet("a") ? 1 : 0)
                        + (opt.isSet("e") ? 1 : 0)
                        + (opt.isSet("v") ? 1 : 0)
                        + (opt.isSet("M") ? 1 : 0);
                if (sel > 1) {
                    System.err.println("keymap: incompatible keymap selection options");
                    return;
                } else if (opt.isSet("a")) {
                    keyMapName = ConsoleReaderImpl.VICMD;
                } else if (opt.isSet("e")) {
                    keyMapName = ConsoleReaderImpl.EMACS;
                } else if (opt.isSet("v")) {
                    keyMapName = ConsoleReaderImpl.VIINS;
                } else if (opt.isSet("M")) {
                    if (opt.args().isEmpty()) {
                        System.err.println("keymap: argument expected: -M");
                        return;
                    }
                    keyMapName = opt.args().remove(0);
                }
                KeyMap map = keyMaps.get(keyMapName);
                if (map == null) {
                    System.err.println("keymap: no such keymap `" + keyMapName + "'");
                    return;
                }
                // Bind
                boolean range = opt.isSet("R");
                if (opt.args().size() % 2 == 1) {
                    System.err.println("keymap: even number of arguments required");
                    return;
                }
                for (int i = 0; i < opt.args().size(); i += 2) {
                    Binding out = opt.isSet("s")
                            ? new Macro(KeyMap.translate(opt.args().get(i + 1)))
                            : new Reference(opt.args().get(i + 1));
                    if (range) {
                        Collection<String> r = KeyMap.range(opt.args().get(i));
                        if (r == null) {
                            System.err.println("keymap: malformed key range `" + opt.args().get(i) + "'");
                            return;
                        }
                        map.bind(out, r);
                    } else {
                        String in = KeyMap.translate(opt.args().get(i));
                        map.bind(out, in);
                    }
                }
                if (opt.isSet("e") || opt.isSet("v")) {
                    keyMaps.put(ConsoleReaderImpl.MAIN, map);
                }
            }
            else {
                // Select keymap
                String keyMapName = ConsoleReaderImpl.MAIN;
                int sel = (opt.isSet("a") ? 1 : 0)
                        + (opt.isSet("e") ? 1 : 0)
                        + (opt.isSet("v") ? 1 : 0)
                        + (opt.isSet("M") ? 1 : 0);
                if (sel > 1) {
                    System.err.println("keymap: incompatible keymap selection options");
                    return;
                } else if (opt.isSet("a")) {
                    keyMapName = ConsoleReaderImpl.VICMD;
                } else if (opt.isSet("e")) {
                    keyMapName = ConsoleReaderImpl.EMACS;
                } else if (opt.isSet("v")) {
                    keyMapName = ConsoleReaderImpl.VIINS;
                } else if (opt.isSet("M")) {
                    if (opt.args().isEmpty()) {
                        System.err.println("keymap: argument expected: -M");
                        return;
                    }
                    keyMapName = opt.args().remove(0);
                }
                KeyMap map = keyMaps.get(keyMapName);
                if (map == null) {
                    System.err.println("keymap: no such keymap `" + keyMapName + "'");
                    return;
                }
                // Display
                boolean prefix = opt.isSet("p");
                boolean commands = opt.isSet("L");
                if (prefix && opt.args().isEmpty()) {
                    System.err.println("keymap: option -p requires a prefix string");
                    return;
                }
                if (opt.args().size() > 0 || !opt.isSet("e") && !opt.isSet("v")) {
                    Map<String, Binding> bound = map.getBoundKeys();
                    String seq = opt.args().size() > 0 ? KeyMap.translate(opt.args().get(0)) : null;
                    Map.Entry<String, Binding> begin = null;
                    String last = null;
                    Iterator<Entry<String, Binding>> iterator = bound.entrySet().iterator();
                    while (iterator.hasNext()) {
                        Map.Entry<String, Binding> entry = iterator.next();
                        String key = entry.getKey();
                        if (seq == null
                                || prefix && key.startsWith(seq) && !key.equals(seq)
                                || !prefix && key.equals(seq)) {
                            if (begin != null || !iterator.hasNext()) {
                                String n = (last.length() > 1 ? last.substring(0, last.length() - 1) : "") + (char) (last.charAt(last.length() - 1) + 1);
                                if (key.equals(n) && entry.getValue().equals(begin.getValue())) {
                                    last = key;
                                } else {
                                    // We're not in a range, so we need to close the previous range
                                    StringBuilder sb = new StringBuilder();
                                    if (commands) {
                                        sb.append("keymap -M ");
                                        sb.append(keyMapName);
                                        sb.append(" ");
                                    }
                                    if (begin.getKey().equals(last)) {
                                        sb.append(KeyMap.display(last));
                                        sb.append(" ");
                                        displayValue(sb, begin.getValue());
                                        System.out.println(sb.toString());
                                    } else {
                                        if (commands) {
                                            sb.append("-R ");
                                        }
                                        sb.append(KeyMap.display(begin.getKey()));
                                        sb.append("-");
                                        sb.append(KeyMap.display(last));
                                        sb.append(" ");
                                        displayValue(sb, begin.getValue());
                                        System.out.println(sb.toString());
                                    }
                                    begin = entry;
                                    last = key;
                                }
                            } else {
                                begin = entry;
                                last = key;
                            }
                        }
                    }
                }
                if (opt.isSet("e") || opt.isSet("v")) {
                    keyMaps.put(ConsoleReaderImpl.MAIN, map);
                }
            }
        }

        public void setopt(CommandSession session, String[] argv) {
            final String[] usage = {
                    "setopt -  set options",
                    "Usage: setopt [-m] option ...",
                    "       setopt",
                    "  -? --help                       Displays command help",
                    "  -m                              Use pattern matching"
            };
            org.apache.felix.gogo.options.Option opt = Options.compile(usage).parse(argv);
            if (opt.isSet("help")) {
                opt.usage();
                return;
            }
            if (opt.args().isEmpty()) {
                ConsoleReaderImpl reader = getReader(session);
                for (Option option : Option.values()) {
                    if (reader.isSet(option) != option.isDef()) {
                        System.out.println((option.isDef() ? "no-" : "") + option.toString().toLowerCase().replace('_', '-'));
                    }
                }
            }
            else {
                boolean match = opt.isSet("m");
                doSetOpts(session, opt.args(), match, true);
            }
        }

        public void unsetopt(CommandSession session, String[] argv) {
            final String[] usage = {
                    "unsetopt -  unset options",
                    "Usage: unsetopt [-m] option ...",
                    "       unsetopt",
                    "  -? --help                       Displays command help",
                    "  -m                              Use pattern matching"
            };
            org.apache.felix.gogo.options.Option opt = Options.compile(usage).parse(argv);
            if (opt.isSet("help")) {
                opt.usage();
                return;
            }
            if (opt.args().isEmpty()) {
                ConsoleReaderImpl reader = getReader(session);
                for (Option option : Option.values()) {
                    if (reader.isSet(option) == option.isDef()) {
                        System.out.println((option.isDef() ? "no-" : "") + option.toString().toLowerCase().replace('_', '-'));
                    }
                }
            }
            else {
                boolean match = opt.isSet("m");
                doSetOpts(session, opt.args(), match, false);
            }
        }

        public List<Candidate> __files(CommandSession session) {
            ParsedLine line = getParsedLine(session);
            ConsoleReader reader = getReader(session);
            List<Candidate> candidates = new ArrayList<Candidate>();
            new FilesCompleter(session).complete(reader, line, candidates);
            return candidates;
        }

        public List<Candidate> __directories(CommandSession session) {
            ParsedLine line = getParsedLine(session);
            ConsoleReader reader = getReader(session);
            List<Candidate> candidates = new ArrayList<Candidate>();
            new DirectoriesCompleter(session).complete(reader, line, candidates);
            return candidates;
        }

        public void __usage_completion(CommandSession session, String command) throws Exception {
            Object func = session.get(command.contains(":") ? command : "*:" + command);
            if (func instanceof Function) {
                ByteArrayInputStream bais = new ByteArrayInputStream(new byte[0]);
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                ByteArrayOutputStream baes = new ByteArrayOutputStream();
                CommandSession ts = ((CommandSessionImpl) session).processor().createSession(bais, new PrintStream(baos), new PrintStream(baes));
                ts.execute(command + " --help");

                String regex = "(?x)\\s*" + "(?:-([^-]))?" +  // 1: short-opt-1
                        "(?:,?\\s*-(\\w))?" +                 // 2: short-opt-2
                        "(?:,?\\s*--(\\w[\\w-]*)(=\\w+)?)?" + // 3: long-opt-1 and 4:arg-1
                        "(?:,?\\s*--(\\w[\\w-]*))?" +         // 5: long-opt-2
                        ".*?(?:\\(default=(.*)\\))?\\s*" +    // 6: default
                        "(.*)";                               // 7: description
                Pattern pattern = Pattern.compile(regex);
                for (String l : baes.toString().split("\n")) {
                    Matcher matcher = pattern.matcher(l);
                    if (matcher.matches()) {
                        List<String> args = new ArrayList<String>();
                        if (matcher.group(1) != null) {
                            args.add("--short-option");
                            args.add(matcher.group(1));
                        }
                        if (matcher.group(3) != null) {
                            args.add("--long-option");
                            args.add(matcher.group(1));
                        }
                        if (matcher.group(4) != null) {
                            args.add("--argument");
                            args.add("");
                        }
                        if (matcher.group(7) != null) {
                            args.add("--description");
                            args.add(matcher.group(7));
                        }
                        complete(session, args.toArray(new String[args.size()]));
                    }
                }
            }
        }

        private void doSetOpts(CommandSession session, List<String> options, boolean match, boolean set) {
            ConsoleReaderImpl reader = getReader(session);
            for (String name : options) {
                String tname = name.toLowerCase().replaceAll("[-_]", "");
                if (match) {
                    tname = tname.replaceAll("\\*", "[a-z]*");
                    tname = tname.replaceAll("\\?", "[a-z]");
                }
                boolean found = false;
                for (org.jline.ConsoleReader.Option option : org.jline.ConsoleReader.Option.values()) {
                    String optName = option.name().toLowerCase().replaceAll("[-_]", "");
                    if (match ? optName.matches(tname) : optName.equals(tname)) {
                        if (set) {
                            reader.setOpt(option);
                        } else {
                            reader.unsetOpt(option);
                        }
                        found = true;
                        if (!match) {
                            break;
                        }
                    } else if (match ? ("no" + optName).matches(tname) : ("no" + optName).equals(tname)) {
                        if (set) {
                            reader.unsetOpt(option);
                        } else {
                            reader.setOpt(option);
                        }
                        if (!match) {
                            found = true;
                        }
                        break;
                    }
                }
                if (!found) {
                    System.err.println("No matching option: " + name);
                }
            }
        }

        private void displayValue(StringBuilder sb, Object value) {
            if (value == null) {
                sb.append("undefined-key");
            } else if (value instanceof Macro) {
                sb.append(KeyMap.display(((Macro) value).getSequence()));
            } else if (value instanceof Reference) {
                sb.append(((Reference) value).name());
            } else {
                sb.append(value.toString());
            }
        }

    }

}
