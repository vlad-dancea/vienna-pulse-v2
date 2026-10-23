/// Records each import in `public.imports`, which outlives the timetable schema every import
/// replaces, since a failed import otherwise leaves the previous timetable in place without a sign.
@NullMarked
package com.vladdancea.viennapulse.importer.history;

import org.jspecify.annotations.NullMarked;
