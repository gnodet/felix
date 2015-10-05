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

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.net.URI;
import java.net.URL;
import java.net.URLConnection;
import java.nio.CharBuffer;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.TreeMap;

import org.apache.felix.gogo.options.Option;
import org.apache.felix.gogo.options.Options;
import org.apache.felix.gogo.runtime.CommandProcessorImpl;
import org.apache.felix.gogo.runtime.CommandProxy;
import org.apache.felix.gogo.runtime.CommandSessionImpl;
import org.apache.felix.gogo.runtime.Reflective;
import org.apache.felix.service.command.CommandProcessor;
import org.apache.felix.service.command.CommandSession;
import org.apache.felix.service.command.Descriptor;
import org.apache.felix.service.command.Function;
import org.apache.felix.service.command.Parameter;

public class Shell
{
    static final String[] functions = { "gosh", "sh", "source", "history", "help" };

    private final URI baseURI;
    private final String profile;
    private final Context context;
    private final CommandProcessor processor;
    private final History history;

    public interface Context
    {
        String getProperty(String name);
        void stop() throws Exception;
    }

    public Shell(Context context, CommandProcessor processor)
    {
        this(context, processor, null);
    }

    public Shell(Context context, CommandProcessor processor, String profile)
    {
        this.context = context;
        this.processor = processor;
        String baseDir = context.getProperty("gosh.home");
        baseDir = (baseDir == null) ? context.getProperty("user.dir") : baseDir;
        this.baseURI = new File(baseDir).toURI();
        this.profile = profile != null ? profile : "gosh_profile";
        this.history = new History();
    }

    public Object gosh(final CommandSession session, String[] argv) throws Exception
    {
        final String[] usage = {
                "gosh - execute script with arguments in a new session",
                "  args are available as session variables $1..$9 and $args.",
                "Usage: gosh [OPTIONS] [script-file [args..]]",
                "  -c --command             pass all remaining args to sub-shell",
                "     --nointeractive       don't start interactive session",
                "     --login               login shell (same session, reads etc/gosh_profile)",
                "  -s --noshutdown          don't shutdown framework when script completes",
                "  -x --xtrace              echo commands before execution",
                "  -? --help                show help",
                "If no script-file, an interactive shell is started, type $D to exit." };

        Option opt = Options.compile(usage).setOptionsFirst(true).parse(argv);
        List<String> args = opt.args();

        boolean login = opt.isSet("login");
        boolean interactive = !opt.isSet("nointeractive");

        if (opt.isSet("help"))
        {
            opt.usage();
            if (login && !opt.isSet("noshutdown"))
            {
                shutdown();
            }
            return null;
        }

        if (opt.isSet("command") && args.isEmpty())
        {
            throw opt.usageError("option --command requires argument(s)");
        }

        CommandSession newSession = (login ? session : processor.createSession(
            session.getKeyboard(), session.getConsole(), System.err));

        if (opt.isSet("xtrace"))
        {
            newSession.put("echo", true);
        }

        // export variables starting with upper-case to newSession
        for (String key : getVariables(session))
        {
            if (key.matches("[.]?[A-Z].*"))
            {
                newSession.put(key, session.get(key));
            }
        }

        Runnable console = null;
        if (args.isEmpty() && interactive)
        {
            console = console(processor, newSession);
        }

        if (login || interactive)
        {
            URI uri = baseURI.resolve("etc/" + profile);
            if (!new File(uri).exists())
            {
                URL url = getClass().getResource("/ext/" + profile);
                if (url == null) {
                    url = getClass().getResource("/" + profile);
                }
                uri = (url == null) ? null : url.toURI();
            }
            if (uri != null)
            {
                source(newSession, uri.toString());
            }
        }

        Object result = null;

        if (args.isEmpty())
        {
            if (interactive)
            {
                console.run();
            }
        }
        else
        {
            CharSequence program;

            if (opt.isSet("command"))
            {
                StringBuilder buf = new StringBuilder();
                for (String arg : args)
                {
                    if (buf.length() > 0)
                    {
                        buf.append(' ');
                    }
                    buf.append(arg);
                }
                program = buf;
            }
            else
            {
                URI script = cwd(session).resolve(args.remove(0));

                // set script arguments
                newSession.put("0", script);
                newSession.put("args", args);

                for (int i = 0; i < args.size(); ++i)
                {
                    newSession.put(String.valueOf(i + 1), args.get(i));
                }

                program = readScript(script);
            }

            result = newSession.execute(program);
        }

        if (login && interactive && !opt.isSet("noshutdown"))
        {
            System.out.println("gosh: stopping framework");
            shutdown();
        }

        return result;
    }

    public Object sh(final CommandSession session, String[] argv) throws Exception
    {
        return gosh(session, argv);
    }

    private void shutdown() throws Exception
    {
        context.stop();
    }

    public Object source(CommandSession session, String script) throws Exception
    {
        URI uri = cwd(session).resolve(script);
        session.put("0", uri);
        try
        {
            return session.execute(readScript(uri));
        }
        finally
        {
            session.put("0", null); // API doesn't support remove
        }
    }

    protected Runnable console(CommandProcessor processor, CommandSession session)
    {
        Runnable console;
        try {
            console = new JLineConsole(processor, session);
        } catch (Throwable t) {
            console = new Console(session, history);
            ((CommandProcessorImpl) processor).addCommand("gogo", this, "history");
        }
        return console;
    }

    static CharSequence readScript(URI script) throws Exception
    {
        URLConnection conn = script.toURL().openConnection();
        int length = conn.getContentLength();

        if (length == -1)
        {
            System.err.println("eek! unknown Contentlength for: " + script);
            length = 10240;
        }

        InputStream in = conn.getInputStream();
        CharBuffer cbuf = CharBuffer.allocate(length);
        Reader reader = new InputStreamReader(in);
        reader.read(cbuf);
        in.close();
        cbuf.rewind();

        return cbuf;
    }

    @SuppressWarnings("unchecked")
    static Set<String> getVariables(CommandSession session)
    {
        return (Set<String>) session.get(".variables");
    }

    static URI cwd(CommandSession session)
    {
        return Posix._pwd(session).toURI(); // _cwd is set by felixcommands:cd
    }

    public String[] history() {
        Iterator<String> history = this.history.getHistory();
        List<String> lines = new ArrayList<String>();
        for (int i = 1; history.hasNext(); i++) {
            lines.add(String.format("%5d  %s", i, history.next()));
        }
        return lines.toArray(new String[lines.size()]);
    }

    private Map<String, List<Method>> getCommands(CommandSession session) {
        Map<String, List<Method>> commands = new TreeMap<String, List<Method>>();
        Set<String> names = (Set<String>) session.get(CommandSessionImpl.COMMANDS);
        for (String name : names) {
            Function function = (Function) session.get(name);
            if (function instanceof CommandProxy) {
                Object target = ((CommandProxy) function).getTarget();
                List<Method> methods = new ArrayList<Method>();
                String func = name.substring(name.indexOf(':') + 1).toLowerCase();
                List<String> funcs = new ArrayList<String>();
                funcs.add("is" + func);
                funcs.add("get" + func);
                funcs.add("set" + func);
                if (Reflective.KEYWORDS.contains(func)) {
                    funcs.add("_" + func);
                } else {
                    funcs.add(func);
                }
                for (Method method : target.getClass().getMethods()) {
                    if (funcs.contains(method.getName().toLowerCase())) {
                        methods.add(method);
                    }
                }
                commands.put(name, methods);
                ((CommandProxy) function).ungetTarget();
            }
        }
        return commands;
    }

    @Descriptor("displays available commands")
    public void help(CommandSession session)
    {
        Map<String, List<Method>> commands = getCommands(session);
        for (String name : commands.keySet())
        {
            System.out.println(name);
        }
    }

    @Descriptor("displays information about a specific command")
    public void help(CommandSession session, @Descriptor("target command") String name)
    {
        Map<String, List<Method>> commands = getCommands(session);

        List<Method> methods = null;

        // If the specified command doesn't have a scope, then
        // search for matching methods by ignoring the scope.
        int scopeIdx = name.indexOf(':');
        if (scopeIdx < 0)
        {
            for (Entry<String, List<Method>> entry : commands.entrySet())
            {
                String k = entry.getKey().substring(entry.getKey().indexOf(':') + 1);
                if (name.equals(k))
                {
                    name = entry.getKey();
                    methods = entry.getValue();
                    break;
                }
            }
        }
        // Otherwise directly look up matching methods.
        else
        {
            methods = commands.get(name);
        }

        if ((methods != null) && (methods.size() > 0))
        {
            for (Method m : methods)
            {
                Descriptor d = m.getAnnotation(Descriptor.class);
                if (d == null)
                {
                    System.out.println("\n" + m.getName());
                }
                else
                {
                    System.out.println("\n" + m.getName() + " - " + d.value());
                }

                System.out.println("   scope: " + name.substring(0, name.indexOf(':')));

                // Get flags and options.
                Class<?>[] paramTypes = m.getParameterTypes();
                Map<String, Parameter> flags = new TreeMap<String, Parameter>();
                Map<String, String> flagDescs = new TreeMap<String, String>();
                Map<String, Parameter> options = new TreeMap<String, Parameter>();
                Map<String, String> optionDescs = new TreeMap<String, String>();
                List<String> params = new ArrayList<String>();
                Annotation[][] anns = m.getParameterAnnotations();
                for (int paramIdx = 0; paramIdx < anns.length; paramIdx++)
                {
                    Class<?> paramType = m.getParameterTypes()[paramIdx];
                    if (paramType == CommandSession.class) {
                        /* Do not bother the user with a CommandSession. */
                        continue;
                    }
                    Parameter p = findAnnotation(anns[paramIdx], Parameter.class);
                    d = findAnnotation(anns[paramIdx], Descriptor.class);
                    if (p != null)
                    {
                        if (p.presentValue().equals(Parameter.UNSPECIFIED))
                        {
                            options.put(p.names()[0], p);
                            if (d != null)
                            {
                                optionDescs.put(p.names()[0], d.value());
                            }
                        }
                        else
                        {
                            flags.put(p.names()[0], p);
                            if (d != null)
                            {
                                flagDescs.put(p.names()[0], d.value());
                            }
                        }
                    }
                    else if (d != null)
                    {
                        params.add(paramTypes[paramIdx].getSimpleName());
                        params.add(d.value());
                    }
                    else
                    {
                        params.add(paramTypes[paramIdx].getSimpleName());
                        params.add("");
                    }
                }

                // Print flags and options.
                if (flags.size() > 0)
                {
                    System.out.println("   flags:");
                    for (Entry<String, Parameter> entry : flags.entrySet())
                    {
                        // Print all aliases.
                        String[] names = entry.getValue().names();
                        System.out.print("      " + names[0]);
                        for (int aliasIdx = 1; aliasIdx < names.length; aliasIdx++)
                        {
                            System.out.print(", " + names[aliasIdx]);
                        }
                        System.out.println("   " + flagDescs.get(entry.getKey()));
                    }
                }
                if (options.size() > 0)
                {
                    System.out.println("   options:");
                    for (Entry<String, Parameter> entry : options.entrySet())
                    {
                        // Print all aliases.
                        String[] names = entry.getValue().names();
                        System.out.print("      " + names[0]);
                        for (int aliasIdx = 1; aliasIdx < names.length; aliasIdx++)
                        {
                            System.out.print(", " + names[aliasIdx]);
                        }
                        System.out.println("   "
                                + optionDescs.get(entry.getKey())
                                + ((entry.getValue().absentValue() == null) ? ""
                                : " [optional]"));
                    }
                }
                if (params.size() > 0)
                {
                    System.out.println("   parameters:");
                    for (Iterator<String> it = params.iterator(); it.hasNext();)
                    {
                        System.out.println("      " + it.next() + "   " + it.next());
                    }
                }
            }
        }
    }

    private static <T extends Annotation> T findAnnotation(Annotation[] anns,
                                                           Class<T> clazz)
    {
        for (int i = 0; (anns != null) && (i < anns.length); i++)
        {
            if (clazz.isInstance(anns[i]))
            {
                return clazz.cast(anns[i]);
            }
        }
        return null;
    }

}
