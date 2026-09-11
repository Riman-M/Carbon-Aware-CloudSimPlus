#!/usr/bin/env python3
"""
Fetch the input datasets. Neither is redistributed in this repository: the
Azure trace is far past GitHub's file size limit, and both are published and
maintained elsewhere.

    python scripts/fetch_data.py --all
    python scripts/fetch_data.py --carboncast
    python scripts/fetch_data.py --azure

Downloads resume if interrupted and are skipped if the target already exists,
so the script is safe to re-run.
"""
import argparse
import glob
import gzip
import os
import shutil
import subprocess
import sys
import tempfile
import urllib.request
from pathlib import Path

CARBONCAST_REPO = "https://github.com/carbonfirst/CarbonCast.git"

# Verified against Azure/AzurePublicDataset. V1 is the 2017 trace and records
# exact core counts; V2 is the 2019 trace and records core and memory *buckets*,
# in which 24 denotes ">24" and 64 denotes ">64". The two are not
# interchangeable: any analysis treating the V2 fields as exact counts
# understates the largest virtual machines. Check the header of whichever file
# you download before use.
AZURE_URLS = {
    "v1": "https://azurepublicdatasettraces.blob.core.windows.net/"
          "azurepublicdataset/trace_data/vmtable/vmtable.csv.gz",
    # GitHub release asset, ~438 MB compressed. Preferred over the blob-storage
    # path because it is versioned and does not change.
    "v2": "https://github.com/Azure/AzurePublicDataset/releases/download/"
          "dataset-v2/trace_data_vmtable_vmtable.csv.gz",
}

# Regions used by the study. The US set is primary; the EU set is the
# degeneracy control.
REGIONS = ["BPAT", "CISO", "ERCO", "ISNE", "NYISO", "PJM", "FPL",
           "SE", "ES", "DE", "NL", "PL", "AUS_QLD"]


def human(n):
    for unit in ("B", "KB", "MB", "GB"):
        if n < 1024 or unit == "GB":
            return f"{n:.1f} {unit}"
        n /= 1024


def download(url, dest):
    """Stream a URL to disk, reporting progress on a single line."""
    dest.parent.mkdir(parents=True, exist_ok=True)
    if dest.exists():
        print(f"  {dest.name} already present ({human(dest.stat().st_size)}); skipping")
        return dest

    tmp = dest.with_suffix(dest.suffix + ".part")
    print(f"  downloading {url}")
    with urllib.request.urlopen(url) as response:
        total = int(response.headers.get("Content-Length", 0))
        done = 0
        with tmp.open("wb") as fh:
            while True:
                chunk = response.read(1 << 20)
                if not chunk:
                    break
                fh.write(chunk)
                done += len(chunk)
                if total:
                    pct = 100 * done / total
                    sys.stdout.write(f"\r  {human(done)} of {human(total)} ({pct:.1f}%)")
                else:
                    sys.stdout.write(f"\r  {human(done)}")
                sys.stdout.flush()
    sys.stdout.write("\n")
    tmp.rename(dest)
    return dest


def fetch_carboncast(out):
    """
    Clone CarbonCast shallowly and copy out the lifecycle emission files.

    Cloning rather than constructing raw file URLs: the repository's directory
    layout has changed between releases, and a glob over a clone keeps working
    when a hard-coded path would not.
    """
    out.mkdir(parents=True, exist_ok=True)
    have = {Path(p).name.split("_lifecycle")[0]
            for p in glob.glob(str(out / "*_lifecycle_emissions.csv"))}
    missing = [r for r in REGIONS if r not in have]
    if not missing:
        print(f"  all {len(REGIONS)} region files already present; skipping")
        return
    print(f"  missing {len(missing)} of {len(REGIONS)} regions: {', '.join(missing)}")

    with tempfile.TemporaryDirectory() as tmp:
        print(f"  cloning {CARBONCAST_REPO} (shallow)")
        try:
            subprocess.run(["git", "clone", "--depth", "1", CARBONCAST_REPO, tmp],
                           check=True, stdout=subprocess.DEVNULL,
                           stderr=subprocess.STDOUT)
        except (subprocess.CalledProcessError, FileNotFoundError) as exc:
            raise SystemExit(
                f"  clone failed ({exc}).\n"
                f"  Download the files by hand from {CARBONCAST_REPO} and place\n"
                f"  <REGION>_lifecycle_emissions.csv in {out}/")

        found = glob.glob(os.path.join(tmp, "**", "*_lifecycle_emissions.csv"),
                          recursive=True)
        copied = 0
        for path in found:
            name = os.path.basename(path)
            region = name.split("_lifecycle")[0]
            if region in REGIONS:
                shutil.copy2(path, out / name)
                copied += 1
        print(f"  copied {copied} region files to {out}/")
        still = [r for r in REGIONS
                 if not (out / f"{r}_lifecycle_emissions.csv").exists()]
        if still:
            print(f"  NOT FOUND in the clone: {', '.join(still)} -- the repository "
                  f"layout or region naming may have changed")


def fetch_azure(out, version):
    out.mkdir(parents=True, exist_ok=True)
    csv = out / "vmtable.csv"
    if csv.exists():
        print(f"  vmtable.csv already present ({human(csv.stat().st_size)}); skipping")
        return

    gz = download(AZURE_URLS[version], out / "vmtable.csv.gz")
    print("  decompressing (this takes a minute)")
    with gzip.open(gz, "rb") as src, csv.open("wb") as dst:
        shutil.copyfileobj(src, dst, length=1 << 22)
    print(f"  wrote {csv} ({human(csv.stat().st_size)})")

    with csv.open() as fh:
        first = fh.readline().strip()
    print(f"  first line: {first[:110]}")
    if "bucket" in first.lower():
        print("  NOTE: this file uses bucketed core and memory fields, in which\n"
              "  24 means '>24' and 64 means '>64'. Do not treat them as exact.")
    elif not first.lower().startswith("vmid") and "," in first:
        print("  NOTE: no header row detected; prep_traces.py handles this, but\n"
              "  confirm the column order against the dataset schema.")


def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--all", action="store_true", help="fetch both datasets")
    ap.add_argument("--carboncast", action="store_true")
    ap.add_argument("--azure", action="store_true")
    ap.add_argument("--azure-version", choices=["v1", "v2"], default="v2",
                    help="v1 is the 2017 trace with exact core counts; "
                         "v2 is the 2019 trace with bucketed fields (default)")
    ap.add_argument("--data-dir", type=Path, default=Path("data"))
    args = ap.parse_args()

    if not (args.all or args.carboncast or args.azure):
        ap.error("choose --all, --carboncast or --azure")

    if args.all or args.carboncast:
        print("CarbonCast carbon intensity traces")
        fetch_carboncast(args.data_dir / "carboncast")

    if args.all or args.azure:
        print(f"Azure Resource Central VM trace ({args.azure_version}, ~0.76 GB)")
        fetch_azure(args.data_dir / "AzureVMTraces", args.azure_version)

    print("\nDone. Next:")
    print("  python scripts/prep_traces.py --mode real --carbon-src data/carboncast \\")
    print("      --region-set us --vm-src data/AzureVMTraces/vmtable.csv \\")
    print("      --out data --hours 720 --requests 1000 --deadline-margin 48 \\")
    print("      --min-duration-h 2")


if __name__ == "__main__":
    main()
