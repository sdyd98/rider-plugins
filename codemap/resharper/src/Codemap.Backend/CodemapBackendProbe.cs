using System;
using System.IO;
using JetBrains.Application;
using JetBrains.Application.Parts;
using JetBrains.ProjectModel;

namespace Codemap.Backend
{
    /// <summary>
    /// TEMPORARY — proves the assembly is discovered and instantiated by the ReSharper host.
    /// </summary>
    /// <remarks>
    /// Writes a file rather than talking to the frontend: whether the host loads a third-party assembly at
    /// all is a separate question from whether an RD protocol is wired correctly, and answering them one at
    /// a time is what keeps a failure readable.
    ///
    /// Two components on purpose. The shell one answers "did the host load us" at backend startup, with no
    /// solution and no UI involved — which is the only form of the question that can be checked without a
    /// human clicking through a project-open. The solution one additionally proves we get a live
    /// <see cref="ISolution"/>, which is what the real work will need.
    /// </remarks>
    public static class Probe
    {
        /// <summary>
        /// An absolute path, not <c>Path.GetTempPath()</c> — on macOS the backend's temp directory is a
        /// per-process <c>/var/folders/…</c> path, so a file written there is effectively invisible.
        /// </summary>
        public const string Path = "/tmp/codemap-backend-loaded.txt";

        public static void Note(string what)
        {
            // Two channels because they fail differently: the file is easy to check but says nothing when
            // the write itself is what failed, and stderr lands in the host's own -err.log, which exists
            // whether or not this process can write anywhere else.
            Console.Error.WriteLine($"[codemap] {what}");
            try
            {
                File.AppendAllText(Path, $"{DateTime.Now:HH:mm:ss} {what}{Environment.NewLine}");
            }
            catch (Exception e)
            {
                Console.Error.WriteLine($"[codemap] could not write {Path}: {e.Message}");
            }
        }
    }

    // Container-created rather than on demand: nothing asks for a probe, so anything lazier would never
    // run and would prove nothing.
    [ShellComponent(Instantiation.ContainerAsyncAnyThreadSafe)]
    public class CodemapShellProbe
    {
        public CodemapShellProbe() => Probe.Note("shell component created");
    }

    [SolutionComponent(Instantiation.ContainerAsyncAnyThreadSafe)]
    public class CodemapBackendProbe
    {
        public CodemapBackendProbe(ISolution solution) =>
            Probe.Note($"solution component created for {solution.Name}");
    }
}
