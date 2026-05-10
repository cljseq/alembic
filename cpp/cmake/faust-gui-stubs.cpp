// SPDX-License-Identifier: EPL-2.0
//
// Definitions for Faust GUI static members.
// GUI.h declares these as statics but provides no .cpp to instantiate them;
// the instantiation must appear in exactly one translation unit per binary.
// clap-helpers-impl.cpp provides a stub fGuiList for FaustGUI but not for GUI.

#include <faust/gui/GUI.h>

ztimedmap GUI::gTimedZoneMap;
