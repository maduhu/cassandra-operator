package com.instaclustr.cassandra.operator;

import ch.qos.logback.classic.Level;
import com.google.common.collect.ImmutableList;
import com.google.inject.*;
import com.instaclustr.cassandra.operator.preflight.Preflight;
import com.instaclustr.cassandra.operator.preflight.PreflightModule;
import com.instaclustr.guava.Application;
import com.instaclustr.guava.EventBusModule;
import com.instaclustr.guava.ServiceManagerModule;
import com.instaclustr.k8s.K8sModule;
import com.instaclustr.picocli.ManifestVersionProvider;
import com.instaclustr.picocli.typeconverter.ExistingFilePathTypeConverter;
import io.kubernetes.client.util.ClientBuilder;
import io.kubernetes.client.util.KubeConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.bridge.SLF4JBridgeHandler;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.Callable;

@Command(name = "cassandra-operator",
        mixinStandardHelpOptions = true,
        description = "A Kubernetes operator for Apache Cassandra.",
        versionProvider = ManifestVersionProvider.class,
        sortOptions = false
)
public class Operator implements Callable<Void> {
    static final Logger logger = LoggerFactory.getLogger(Operator.class);

    static class LoggingOptions {
        @Option(names = {"-v", "--verbose"}, description = {"Be verbose for the com.instaclustr package (enable DEBUG level logging).",
                                                            "Specify @|italic --verbose|@ twice to increase verbosity (enable TRACE level logging).",
                                                            "For other packages configure Logback via @|italic logback.xml|@"})
        boolean[] verbosity;
    }

    static class OperatorOptions {
        @Option(names = {"-n", "--namespace"}, description = "")
        String namespace;
    }

    public static class K8sClientOptions {
        @CommandLine.Option(names = {"-c", "--kube-config"},
                converter = ExistingFilePathTypeConverter.class,
                description = {"Path to the Kubernetes client configuration file.",
                        "To prevent any config file from being used, including the default, set to empty (@|italic --kube-config|@=\"\") or use @|italic --no-kube-config|@."},
                showDefaultValue = CommandLine.Help.Visibility.ALWAYS)
        Path kubeConfig = Paths.get(System.getenv(KubeConfig.ENV_HOME), KubeConfig.KUBEDIR, KubeConfig.KUBECONFIG);

        @CommandLine.Option(names = "--no-kube-config")
        boolean noKubeConfig;

        @CommandLine.Option(names = "--host")
        String host;

        @CommandLine.Option(names = "--insecure-tls")
        boolean disableTlsVerification;
    }

    @Mixin
    LoggingOptions loggingOptions;

    @Mixin
    OperatorOptions operatorOptions;

    @Mixin
    K8sClientOptions k8sClientOptions;

    @Mixin
    K8sVersionValidator.Options versionValidatorOptions;


    public static void main(final String[] args) {
        CommandLine.call(new Operator(), System.err, CommandLine.Help.Ansi.ON, args);
    }

    @Override
    public Void call() throws Exception {
        SLF4JBridgeHandler.removeHandlersForRootLogger();
        SLF4JBridgeHandler.install();

        if (loggingOptions.verbosity != null) {
            final ch.qos.logback.classic.Logger packageLogger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger("com.instaclustr");
            packageLogger.setLevel(Level.TRACE);
        }


//        final KubeConfig kubeConfig;
//        try (var bufferedReader = Files.newBufferedReader(k8sClientOptions.kubeConfig)) {
//            kubeConfig = KubeConfig.loadKubeConfig(bufferedReader);
//        }


        final Injector injector = Guice.createInjector(
                new AbstractModule() {
                    @Override
                    protected void configure() {

                        bind(K8sVersionValidator.Options.class).toInstance(versionValidatorOptions);

//                        bind(KubeConfig.class).toInstance(kubeConfig);
                        try {
                            bind(ClientBuilder.class).toInstance(ClientBuilder.standard());
                        } catch (IOException e) {
                            e.printStackTrace();
                        }
                    }
                },
                new ServiceManagerModule(),
                new EventBusModule(),
                new K8sModule(),
                new PreflightModule(),
                new OperatorModule()
        );

        injector.getInstance(K8sVersionValidator.class).call();

        // run Preflight operations
        injector.getInstance(Preflight.class).call();

        return injector.getInstance(Application.class).call();
    }
}
