"""B2: B's look (navy tile, lime-aqua mark, white + lime wordmark) without sport cues.
Usage: venv/bin/python make_b2.py OUT_DIR"""
import sys

import make_brand as MB
import marks_b2 as M

BASE = dict(MB.CONCEPTS["B-ballplay"])


def concept(geom_fn, name):
    c = dict(BASE)
    c["mark"] = MB._mark(geom_fn)
    c["name"] = name
    return c


MB.CONCEPTS = {
    "B2-pair": concept(M.pair2, "Pair"),
    "B2-spark": concept(M.spark_ring2, "Spark"),
    "B2-fit": concept(M.fit, "Fit"),
}
MB.LABELS = ["B1", "B2", "B3"]
MB.PREVIEW = "preview-b2.png"
MB.TITLE = "WatchMatch  -  B variants, no sport cues"

if __name__ == "__main__":

    MB.main()
