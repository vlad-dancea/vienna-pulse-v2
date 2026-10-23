/// Records each import in `public.imports` and reports through the health endpoint when the
/// timetable gets too old, since a failed import otherwise leaves the previous one in place without
/// a sign.
@NullMarked
package com.vladdancea.viennapulse.importer.history;

import org.jspecify.annotations.NullMarked;
