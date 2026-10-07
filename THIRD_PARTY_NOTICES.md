# Third-party compatibility and acknowledgements

Moka Music Player is an independent project.

## JamesDSP

Moka's DSP design is influenced by the Android audio-processing ecosystem around **JamesDSP**, created by James Fung (`james34602`). Moka implements compatible concepts including multimodal FIR/IIR equalization, partitioned convolution and ViPER-DDC-style coefficient processing.

Upstream project:

- https://github.com/james34602/JamesDSPManager

Moka is not affiliated with, endorsed by, or an official distribution of JamesDSP.

**Source-provenance note:** before store/commercial distribution, maintainers should keep a record showing which DSP code is original, independently reimplemented, or adapted from another project. If any source was copied or adapted, the corresponding upstream license obligations must be followed. File-format compatibility and independent implementation are not the same as copied source.

## ViPER / ViPER-DDC

Moka can import compatible `.vdc` headphone-correction profiles and uses the name **ViPER-DDC** only to describe that compatibility format/ecosystem.

Moka is not an official ViPER4Android application and is not affiliated with the original ViPER developers. Product names and trademarks remain the property of their respective owners.

## MusicBrainz

Optional library enrichment uses the MusicBrainz web service. Moka uses core release metadata for matching and identifiers. MusicBrainz core database data is published under CC0. MusicBrainz is operated by the MetaBrainz Foundation.

- https://musicbrainz.org/
- https://musicbrainz.org/doc/About/Data_License

Moka identifies itself with a meaningful User-Agent and rate-limits web-service requests.

## Cover Art Archive / Internet Archive

Optional artwork enrichment can request front-cover images through the Cover Art Archive. Cover images are served from Internet Archive infrastructure and can remain subject to copyright owned by their respective rights holders. Moka caches requested images locally for the user's library and does not bundle or redistribute a cover-art catalog.

- https://coverartarchive.org/
- https://musicbrainz.org/doc/Cover_Art_Archive/API


## Release audit status

The stable-release dependency and source-provenance review is tracked in:

- `docs/release/DEPENDENCY_LICENSE_INVENTORY.md`
- `docs/release/DSP_SOURCE_PROVENANCE.md`

The direct dependency inventory has been started, but the full resolved/transitive dependency audit and DSP provenance sign-off remain release gates. Acknowledgement of an upstream project here does not by itself determine whether attribution or other license obligations apply to Moka's implementation.
