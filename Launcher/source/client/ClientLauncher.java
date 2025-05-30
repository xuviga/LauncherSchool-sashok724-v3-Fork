package launcher.client;

import com.eclipsesource.json.Json;
import com.eclipsesource.json.JsonObject;
import com.eclipsesource.json.WriterConfig;
import launcher.Launcher;
import launcher.Launcher.Config;
import launcher.LauncherAPI;
import launcher.client.ClientProfile.Version;
import launcher.hasher.DirWatcher;
import launcher.hasher.FileNameMatcher;
import launcher.hasher.HashedDir;
import launcher.helper.*;
import launcher.helper.JVMHelper.OS;
import launcher.request.update.LauncherRequest;
import launcher.serialize.HInput;
import launcher.serialize.HOutput;
import launcher.serialize.signed.SignedObjectHolder;
import launcher.serialize.stream.StreamObject;

import java.io.IOException;
import java.lang.ProcessBuilder.Redirect;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodType;
import java.net.URL;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.security.interfaces.RSAPublicKey;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

public final class ClientLauncher
{
    // Authlib constants
    @LauncherAPI
    public static final String SKIN_URL_PROPERTY = "skinURL";
    @LauncherAPI
    public static final String SKIN_DIGEST_PROPERTY = "skinDigest";
    @LauncherAPI
    public static final String CLOAK_URL_PROPERTY = "cloakURL";
    @LauncherAPI
    public static final String CLOAK_DIGEST_PROPERTY = "cloakDigest";
    private static final String[] EMPTY_ARRAY = new String[0];
    private static final String MAGICAL_INTEL_OPTION = "-XX:HeapDumpPath=ThisTricksIntelDriversForPerformance_javaw.exe_minecraft.exe.heapdump";
    private static final Set<PosixFilePermission> BIN_POSIX_PERMISSIONS = Collections.unmodifiableSet(EnumSet.of(
            PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE, // Owner
            PosixFilePermission.GROUP_READ, PosixFilePermission.GROUP_EXECUTE, // Group
            PosixFilePermission.OTHERS_READ, PosixFilePermission.OTHERS_EXECUTE // Others
    ));
    // Constants
    private static final Path NATIVES_DIR = IOHelper.toPath("natives");
    private static final Path RESOURCEPACKS_DIR = IOHelper.toPath("resourcepacks");
    private static final Pattern UUID_PATTERN = Pattern.compile("-", Pattern.LITERAL);
    // Used to determine from clientside is launched from launcher
    private static final AtomicBoolean LAUNCHED = new AtomicBoolean(false);

    private ClientLauncher()
    {
    }

    @LauncherAPI
    public static boolean isLaunched()
    {
        return LAUNCHED.get();
    }

    public static String jvmProperty(String name, String value)
    {
        return String.format("-D%s=%s", name, value);
    }

    @LauncherAPI
    public static Process launch(Path jvmDir, SignedObjectHolder<HashedDir> jvmHDir,
                                 SignedObjectHolder<HashedDir> assetHDir, SignedObjectHolder<HashedDir> clientHDir,
                                 SignedObjectHolder<ClientProfile> profile, Params params, boolean pipeOutput) throws Throwable
    {
        // Write params file (instead of CLI; Mustdie32 API can't handle command line > 32767 chars)
        LogHelper.debug("Writing ClientLauncher params file");
        Path paramsFile = Files.createTempFile("ClientLauncherParams", ".bin");
        try (HOutput output = new HOutput(IOHelper.newOutput(paramsFile)))
        {
            params.write(output);
            profile.write(output);

            // Write hdirs
            jvmHDir.write(output);
            assetHDir.write(output);
            clientHDir.write(output);
        }

        // Resolve java bin and set permissions
        LogHelper.debug("Resolving JVM binary");
        Path javaBin = IOHelper.resolveJavaBin(jvmDir);
        if (IOHelper.POSIX)
        {
            Files.setPosixFilePermissions(javaBin, BIN_POSIX_PERMISSIONS);
        }

        // Fill CLI arguments
        List<String> args = new LinkedList<>();
        args.add(javaBin.toString());
        args.add(MAGICAL_INTEL_OPTION);
        if (params.ram > 0 && params.ram <= JVMHelper.RAM)
        {
            args.add("-Xms" + params.ram + 'M');
            args.add("-Xmx" + params.ram + 'M');
        }
        args.add(jvmProperty(LogHelper.DEBUG_PROPERTY, Boolean.toString(LogHelper.isDebugEnabled())));
        if (Config.ADDRESS_OVERRIDE != null)
        {
            args.add(jvmProperty(Config.ADDRESS_OVERRIDE_PROPERTY, Config.ADDRESS_OVERRIDE));
        }
        if (JVMHelper.OS_TYPE == OS.MUSTDIE && JVMHelper.OS_VERSION.startsWith("10."))
        {
            LogHelper.debug("MustDie 10 fix is applied");
            args.add(jvmProperty("os.name", "Windows 10"));
            args.add(jvmProperty("os.version", "10.0"));
        }

        // A fucking shitty fix
        args.add(jvmProperty(JVMHelper.JAVA_LIBRARY_PATH, params.clientDir.resolve(NATIVES_DIR).toString()));

        // Add classpath and main class
        Collections.addAll(args, profile.object.getJvmArgs());
        String v = profile.object.getVersion();
        if (Version.compare(v, "1.13") >= 0 && JVMHelper.OS_TYPE == OS.MACOSX)
            Collections.addAll(args, "-XstartOnFirstThread");
        Collections.addAll(args, "-classpath", IOHelper.getCodeSource(ClientLauncher.class).toString(), ClientLauncher.class.getName());
        args.add(paramsFile.toString()); // Add params file path to args

        // Print commandline debug message
        LogHelper.debug("Commandline: " + args);

        // Build client process
        LogHelper.debug("Launching client instance");
        ProcessBuilder builder = new ProcessBuilder(args);
        builder.directory(params.clientDir.toFile());
        builder.inheritIO();
        Map<String, String> env = builder.environment();
        env.put("_JAVA_OPTS", "");
        env.put("_JAVA_OPTIONS", "");
        env.put("JAVA_OPTS", "");
        env.put("JAVA_OPTIONS", "");
        if (pipeOutput)
        {
            builder.redirectErrorStream(true);
            builder.redirectOutput(Redirect.PIPE);
        }

        // Let's rock!
        return builder.start();
    }

    @LauncherAPI
    public static void main(String... args) throws Throwable
    {
        SecurityHelper.verifyCertificates(ClientLauncher.class);
        JVMHelper.verifySystemProperties(ClientLauncher.class, true);
        LogHelper.printVersion("Client Launcher");

        // Resolve params file
        VerifyHelper.verifyInt(args.length, l -> l >= 1, "Missing args: <paramsFile>");
        Path paramsFile = IOHelper.toPath(args[0]);

        // Read and delete params file
        LogHelper.debug("Reading ClientLauncher params file");
        Params params;
        SignedObjectHolder<ClientProfile> profile;
        SignedObjectHolder<HashedDir> jvmHDir, assetHDir, clientHDir;
        RSAPublicKey publicKey = Launcher.getConfig().publicKey;
        try (HInput input = new HInput(IOHelper.newInput(paramsFile)))
        {
            params = new Params(input);
            profile = new SignedObjectHolder<>(input, publicKey, ClientProfile.RO_ADAPTER);

            // Read hdirs
            jvmHDir = new SignedObjectHolder<>(input, publicKey, HashedDir::new);
            assetHDir = new SignedObjectHolder<>(input, publicKey, HashedDir::new);
            clientHDir = new SignedObjectHolder<>(input, publicKey, HashedDir::new);
        }
        finally
        {
            Files.delete(paramsFile);
        }

        // Verify ClientLauncher sign and classpath
        LogHelper.debug("Verifying ClientLauncher sign and classpath");
        SecurityHelper.verifySign(LauncherRequest.BINARY_PATH, params.launcherSign, publicKey);
        URL[] classpath = JVMHelper.getClassPath();
        for (URL classpathURL : classpath)
        {
            Path file = Paths.get(classpathURL.toURI());
            if (!file.startsWith(IOHelper.JVM_DIR) && !file.equals(LauncherRequest.BINARY_PATH))
            {
                throw new SecurityException(String.format("Forbidden classpath entry: '%s'", file));
            }
        }

        // Start client with WatchService monitoring
        boolean digest = !profile.object.isUpdateFastCheck();
        LogHelper.debug("Starting JVM and client WatchService");
        FileNameMatcher assetMatcher = profile.object.getAssetUpdateMatcher();
        FileNameMatcher clientMatcher = profile.object.getClientUpdateMatcher();
        try (DirWatcher jvmWatcher = new DirWatcher(IOHelper.JVM_DIR, jvmHDir.object, null, digest); // JVM Watcher
             DirWatcher assetWatcher = new DirWatcher(params.assetDir, assetHDir.object, assetMatcher, digest);
             DirWatcher clientWatcher = new DirWatcher(params.clientDir, clientHDir.object, clientMatcher, digest))
        {
            // Start WatchService, and only then client
            CommonHelper.newThread("JVM Directory Watcher", true, jvmWatcher).start();
            CommonHelper.newThread("Asset Directory Watcher", true, assetWatcher).start();
            CommonHelper.newThread("Client Directory Watcher", true, clientWatcher).start();

            // Verify current state of all dirs
            verifyHDir(IOHelper.JVM_DIR, jvmHDir.object, null, digest);
            verifyHDir(params.assetDir, assetHDir.object, assetMatcher, digest);
            verifyHDir(params.clientDir, clientHDir.object, clientMatcher, digest);

            launch(profile.object, params);
        }
    }

    @LauncherAPI
    public static String toHash(UUID uuid)
    {
        return UUID_PATTERN.matcher(uuid.toString()).replaceAll("");
    }

    @LauncherAPI
    public static void verifyHDir(Path dir, HashedDir hdir, FileNameMatcher matcher, boolean digest) throws IOException
    {
        if (matcher != null)
        {
            matcher = matcher.verifyOnly();
        }

        // Hash directory and compare (ignore update-only matcher entries, it will break offline-mode)
        HashedDir currentHDir = new HashedDir(dir, matcher, false, digest);
        if (!hdir.diff(currentHDir, matcher).isSame())
        {
            throw new SecurityException(String.format("Forbidden modification: '%s'", IOHelper.getFileName(dir)));
        }
    }

    private static void addClientArgs(Collection<String> args, ClientProfile profile, Params params)
    {
        PlayerProfile pp = params.pp;

        // Add version-dependent args
        String version = profile.getVersion();
        Collections.addAll(args, "--username", pp.username);
        if (Version.compare(version, "1.7.2") >= 0)
        {
            Collections.addAll(args, "--uuid", toHash(pp.uuid));
            Collections.addAll(args, "--accessToken", params.accessToken);

            // Add 1.7.3+ args (user properties, asset index)
            if (Version.compare(version, "1.7.3") >= 0)
            {
                // Add user properties
                if (Version.compare(version, "1.7.4") >= 0)
                {
                    Collections.addAll(args, "--userType", "mojang");
                }
                JsonObject properties = Json.object();
                if (pp.skin != null)
                {
                    properties.add(SKIN_URL_PROPERTY, Json.array(pp.skin.url));
                    properties.add(SKIN_DIGEST_PROPERTY, Json.array(SecurityHelper.toHex(pp.skin.digest)));
                }
                if (pp.cloak != null)
                {
                    properties.add(CLOAK_URL_PROPERTY, Json.array(pp.cloak.url));
                    properties.add(CLOAK_DIGEST_PROPERTY, Json.array(SecurityHelper.toHex(pp.cloak.digest)));
                }
                Collections.addAll(args, "--userProperties", properties.toString(WriterConfig.MINIMAL));

                // Add asset index
                Collections.addAll(args, "--assetIndex", profile.getAssetIndex());
            }
        }
        else
        {
            Collections.addAll(args, "--session", params.accessToken);
        }

        // Add version and dirs args
        Collections.addAll(args, "--version", profile.getVersion());
        Collections.addAll(args, "--gameDir", params.clientDir.toString());
        Collections.addAll(args, "--assetsDir", params.assetDir.toString());
        Collections.addAll(args, "--resourcePackDir", params.clientDir.resolve(RESOURCEPACKS_DIR).toString());
        if (Version.compare(version, "1.9.0") >= 0)
        { // Just to show it in debug screen
            Collections.addAll(args, "--versionType", "KJ-Launcher v" + Launcher.VERSION);
        }

        // Add server args
        if (params.autoEnter)
        {
            Collections.addAll(args, "--server", profile.getServerAddress());
            Collections.addAll(args, "--port", Integer.toString(profile.getServerPort()));
        }

        // Add window size args
        if (params.fullScreen)
        {
            Collections.addAll(args, "--fullscreen", Boolean.toString(true));
        }
        if (params.width > 0 && params.height > 0)
        {
            Collections.addAll(args, "--width", Integer.toString(params.width));
            Collections.addAll(args, "--height", Integer.toString(params.height));
        }
    }

    private static void addClientLegacyArgs(Collection<String> args, ClientProfile profile, Params params)
    {
        args.add(params.pp.username);
        args.add(params.accessToken);

        // Add args for tweaker
        Collections.addAll(args, "--version", profile.getVersion());
        Collections.addAll(args, "--gameDir", params.clientDir.toString());
        Collections.addAll(args, "--assetsDir", params.assetDir.toString());
    }

    private static void launch(ClientProfile profile, Params params) throws Throwable
    {
        // Add client args
        Collection<String> args = new LinkedList<>();
        if (Version.compare(profile.getVersion(), "1.6.0") >= 0)
        {
            addClientArgs(args, profile, params);
        }
        else
        {
            addClientLegacyArgs(args, profile, params);
        }
        Collections.addAll(args, profile.getClientArgs());
        LogHelper.debug("Args: " + args);

        // Add client classpath
        URL[] classPath = resolveClassPath(params.clientDir, profile.getClassPath());
        for (URL url : classPath)
        {
            JVMHelper.addClassPath(url);
        }

        // Resolve main class and method
        Class<?> mainClass = Class.forName(profile.getMainClass());
        MethodHandle mainMethod = JVMHelper.LOOKUP.findStatic(mainClass, "main", MethodType.methodType(void.class, String[].class))
                .asFixedArity();

        // Invoke main method with exception wrapping
        LAUNCHED.set(true);
        JVMHelper.fullGC();
        System.setProperty("minecraft.launcher.brand", "KeeperJerry's NekroLauncher");
        System.setProperty("minecraft.launcher.version", Launcher.VERSION);
        System.setProperty("minecraft.applet.TargetDirectory", params.clientDir.toString()); // For 1.5.2
        mainMethod.invoke((Object) args.toArray(EMPTY_ARRAY));
    }

    private static URL[] resolveClassPath(Path clientDir, String... classPath) throws IOException
    {
        Collection<Path> result = new LinkedList<>();
        for (String classPathEntry : classPath)
        {
            Path path = clientDir.resolve(IOHelper.toPath(classPathEntry));
            if (IOHelper.isDir(path))
            { // Recursive walking and adding
                IOHelper.walk(path, new ClassPathFileVisitor(result), false);
                continue;
            }
            result.add(path);
        }
        return result.stream().map(IOHelper::toURL).toArray(URL[]::new);
    }

    public static final class Params extends StreamObject
    {
        // Client paths
        @LauncherAPI
        public final Path assetDir;
        @LauncherAPI
        public final Path clientDir;

        // Client params
        @LauncherAPI
        public final PlayerProfile pp;
        @LauncherAPI
        public final String accessToken;
        @LauncherAPI
        public final boolean autoEnter;
        @LauncherAPI
        public final boolean fullScreen;
        @LauncherAPI
        public final int ram;
        @LauncherAPI
        public final int width;
        @LauncherAPI
        public final int height;
        private final byte[] launcherSign;

        @LauncherAPI
        public Params(byte[] launcherSign, Path assetDir, Path clientDir, PlayerProfile pp, String accessToken,
                      boolean autoEnter, boolean fullScreen, int ram, int width, int height)
        {
            this.launcherSign = launcherSign.clone();

            // Client paths
            this.assetDir = assetDir;
            this.clientDir = clientDir;

            // Client params
            this.pp = pp;
            this.accessToken = SecurityHelper.verifyToken(accessToken);
            this.autoEnter = autoEnter;
            this.fullScreen = fullScreen;
            this.ram = ram;
            this.width = width;
            this.height = height;
        }

        @LauncherAPI
        public Params(HInput input) throws IOException
        {
            launcherSign = input.readByteArray(-SecurityHelper.RSA_KEY_LENGTH);

            // Client paths
            assetDir = IOHelper.toPath(input.readString(0));
            clientDir = IOHelper.toPath(input.readString(0));

            // Client params
            pp = new PlayerProfile(input);
            int length = input.readInt();
            accessToken = SecurityHelper.verifyToken(input.readASCII(-length));
            autoEnter = input.readBoolean();
            fullScreen = input.readBoolean();
            ram = input.readVarInt();
            width = input.readVarInt();
            height = input.readVarInt();
        }

        @Override
        public void write(HOutput output) throws IOException
        {
            output.writeByteArray(launcherSign, -SecurityHelper.RSA_KEY_LENGTH);

            // Client paths
            output.writeString(assetDir.toString(), 0);
            output.writeString(clientDir.toString(), 0);

            // Client params
            pp.write(output);
            output.writeInt(accessToken.length());
            output.writeASCII(accessToken, -accessToken.length());
            output.writeBoolean(autoEnter);
            output.writeBoolean(fullScreen);
            output.writeVarInt(ram);
            output.writeVarInt(width);
            output.writeVarInt(height);
        }
    }

    private static final class ClassPathFileVisitor extends SimpleFileVisitor<Path>
    {
        private final Collection<Path> result;

        private ClassPathFileVisitor(Collection<Path> result)
        {
            this.result = result;
        }

        @Override
        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException
        {
            if (IOHelper.hasExtension(file, "jar") || IOHelper.hasExtension(file, "zip"))
            {
                result.add(file);
            }
            return super.visitFile(file, attrs);
        }
    }
}

// Н@хуя это здесь? *facepam* И главное нахуя?
