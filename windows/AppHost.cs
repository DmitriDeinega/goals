using Goals_Windows.Services;
using Goals_Windows.Services.Api;
using Goals_Windows.Services.Session;
using Goals_Windows.Services.Sse;
using Goals_Windows.Services.State;
using Goals_Windows.ViewModels;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Hosting;
using Microsoft.Extensions.Logging;
using Serilog;
using System;
using System.IO;

namespace Goals_Windows;

/// <summary>
/// Builds the application's dependency-injection container and configures
/// logging. Services here are the ones the entire app depends on — windows,
/// view models, API/SSE/state services. Each phase adds to the registrations.
/// </summary>
public static class AppHost
{
    public static IHost Build()
    {
        var builder = Host.CreateApplicationBuilder();

        ConfigureLogging(builder);
        ConfigureServices(builder.Services);

        return builder.Build();
    }

    private static void ConfigureLogging(HostApplicationBuilder builder)
    {
        var logDir = Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
            "Goals",
            "logs");
        Directory.CreateDirectory(logDir);

        var serilog = new LoggerConfiguration()
            .MinimumLevel.Debug()
            .Enrich.FromLogContext()
            .WriteTo.Debug()
            .WriteTo.File(
                path: Path.Combine(logDir, "goals-.log"),
                rollingInterval: RollingInterval.Day,
                retainedFileCountLimit: 14,
                shared: true)
            .CreateLogger();

        Log.Logger = serilog;

        builder.Logging.ClearProviders();
        builder.Logging.AddSerilog(serilog, dispose: true);
    }

    private static void ConfigureServices(IServiceCollection services)
    {
        // Core services
        services.AddSingleton<SessionIdProvider>();
        services.AddSingleton<GoalsApiClient>();
        services.AddSingleton<IGoalsState, GoalsStateStore>();
        services.AddSingleton<ToggleService>();
        services.AddSingleton<GoalsCrudService>();

        // Background services
        services.AddHostedService<GoalsSseService>();

        // Windows + view models
        services.AddSingleton<MainWindow>();
        services.AddTransient<MainPageViewModel>();
        services.AddTransient<GoalsPageViewModel>();
        services.AddTransient<GoalFormViewModel>();
    }
}
