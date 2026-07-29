using JetBrains.Application.BuildScript.Application.Zones;
using JetBrains.ReSharper.Psi;

namespace Codemap.Backend
{
    /// <summary>
    /// Which parts of the product this assembly needs before it may load.
    /// </summary>
    /// <remarks>
    /// Zones are ReSharper's way of keeping a plugin from being activated in a product that lacks what it
    /// depends on. Ours needs the C++ language support; in a Rider without it this simply never loads,
    /// which is the correct outcome rather than a crash at first use.
    ///
    /// The C++ *language* zone, not Rider's `IReSharperHostCppFeatureZone`. Both are listed among the
    /// host's discovered zones, but only this one is actually active: requiring the other silently
    /// produced no components at all — the assembly was catalogued and then every part in it filtered
    /// out, with nothing in any log to say so. Measured, not assumed; change it only against a probe.
    /// </remarks>
    // Spelled out because the class below is also called ZoneMarker — that is the convention the host looks
    // for, and the short form would resolve to this class instead of the attribute.
    [ZoneMarkerAttribute]
    public class ZoneMarker : IRequire<ILanguageCppZone>
    {
    }
}
