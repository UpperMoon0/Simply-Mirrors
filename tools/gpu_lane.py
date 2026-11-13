"""Exclusive, bounded native Windows shader GPU lane across independent jobs."""
from contextlib import contextmanager
import os


@contextmanager
def exclusive_graphics_lane(timeout_ms=25 * 60 * 1000):
    if os.name != "nt":
        # Native Windows Session-5 is the only qualified physical-GPU lane.
        # Linux visual tests use separate Xvfb instances managed by Jenkins.
        yield
        return
    import ctypes
    kernel = ctypes.WinDLL("kernel32", use_last_error=True)
    kernel.CreateMutexW.argtypes = (ctypes.c_void_p, ctypes.c_int, ctypes.c_wchar_p)
    kernel.CreateMutexW.restype = ctypes.c_void_p
    kernel.WaitForSingleObject.argtypes = (ctypes.c_void_p, ctypes.c_uint32)
    kernel.WaitForSingleObject.restype = ctypes.c_uint32
    kernel.ReleaseMutex.argtypes = (ctypes.c_void_p,)
    kernel.ReleaseMutex.restype = ctypes.c_int
    kernel.CloseHandle.argtypes = (ctypes.c_void_p,)
    kernel.CloseHandle.restype = ctypes.c_int
    # Session-local namespace: both qualified Jenkins and isolated manual
    # graphics processes inherit the same restricted interactive Session 5.
    handle = kernel.CreateMutexW(None, 0, "Local\\SimplyMirrorsNativeShaderGpuLane")
    if not handle:
        raise OSError(ctypes.get_last_error(), "Cannot create graphics lane mutex")
    acquired = False
    try:
        status = kernel.WaitForSingleObject(handle, timeout_ms)
        if status not in (0, 0x80):
            raise TimeoutError("Qualified GPU lane occupied or mutex wait failed")
        acquired = True
        yield
    finally:
        if acquired:
            kernel.ReleaseMutex(handle)
        kernel.CloseHandle(handle)
