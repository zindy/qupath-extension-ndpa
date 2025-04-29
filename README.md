# QuPath extension ndpa

This repo contains an extension to import Hamamatsu NDPA annotations into [QuPath](https://qupath.github.io).

It also allows to export polygon annotations with complex geometries back into a NDPA file that can be opened
with NDP.view.

**Please note:** As a precaution, any existing NDPA file will be backed up.

**Developer's notes:** For now, this extension may only work with QuPath 0.6.0 (rc4). This is because it relies on the OpenSlide library to read some specific TIFF tags not available through using ImageIO. Namely, the culprits are TIFF tag 65422 "hamamatsu.XOffsetFromSlideCentre" and TIFF tag 65423 "hamamatsu.YOffsetFromSlideCentre" according to OpenSlide.

## Build the extension

Building the extension with Gradle should be pretty easy - you don't even need to install Gradle separately, because the 
[Gradle Wrapper](https://docs.gradle.org/current/userguide/gradle_wrapper.html) will take care of that.

Open a command prompt, navigate to where the code lives, and use
```bash
gradlew build
```

The built extension should be found inside `build/libs`.
You can drag this onto QuPath to install it.
You'll be prompted to create a user directory if you don't already have one.


## Getting help

For questions about QuPath and/or creating new extensions, please use the forum at https://forum.image.sc/tag/qupath

------

## License

Not much code in here, but I want to keep it open, so am releasing this work under the GPL v3 License.
