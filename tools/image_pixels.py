"""Version-safe flattened RGB pixel iterator for Pillow 12.0 through 14+."""
import warnings


def flattened_pixels(image):
    modern = getattr(image, "get_flattened_data", None)
    if modern is not None:
        return modern()
    # Pillow 12.0 (pinned in Jenkins) does not yet offer the replacement.
    with warnings.catch_warnings():
        warnings.filterwarnings("ignore", message=r"Image\.Image\.getdata is deprecated",
                                category=DeprecationWarning)
        return image.getdata()
