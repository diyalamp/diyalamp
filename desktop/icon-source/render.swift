// Rebuild the macOS icon: swift desktop/icon-source/render.swift [--preview]
// The approved profile artwork supplies the exact lamp geometry and placement.
import AppKit
import CoreGraphics
import ImageIO
import UniformTypeIdentifiers

let sourceDirectory = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
let sourceURL = sourceDirectory.appendingPathComponent("profile-icon.png")
let outputURL = sourceDirectory.deletingLastPathComponent()
    .appendingPathComponent("src/main/resources/app-icon.png")
let source = CGImageSourceCreateWithURL(sourceURL as CFURL, nil)!
let original = CGImageSourceCreateImageAtIndex(source, 0, nil)!
let size = original.width
precondition(size == 460 && original.height == size, "Expected the approved 460px profile icon")
let colorSpace = CGColorSpaceCreateDeviceRGB()
let bitmapInfo = CGImageAlphaInfo.premultipliedLast.rawValue
func surfaceColor(_ value: CGFloat) -> CGColor {
    CGColor(colorSpace: colorSpace, components: [value / 255, value / 255, value / 255, 1])!
}
func bitmap(_ width: Int, _ height: Int) -> CGContext {
    CGContext(data: nil, width: width, height: height, bitsPerComponent: 8,
        bytesPerRow: width * 4, space: colorSpace, bitmapInfo: bitmapInfo)!
}
let reference = bitmap(size, size)
reference.interpolationQuality = .none
reference.draw(original, in: CGRect(x: 0, y: 0, width: size, height: size))
let originalPixels = reference.data!.assumingMemoryBound(to: UInt8.self)

// The approved profile is white-matted. Flood only white connected to the canvas
// boundary, preserving enclosed highlights within the flame and bowl.
var background = [Bool](repeating: false, count: size * size)
var queue = [Int]()
func isWhite(_ index: Int) -> Bool {
    let offset = index * 4
    return originalPixels[offset] == 255 && originalPixels[offset + 1] == 255
        && originalPixels[offset + 2] == 255
}
func enqueue(_ index: Int) {
    if !background[index] && isWhite(index) { background[index] = true; queue.append(index) }
}
for coordinate in 0..<size {
    enqueue(coordinate); enqueue((size - 1) * size + coordinate)
    enqueue(coordinate * size); enqueue(coordinate * size + size - 1)
}
var head = 0
while head < queue.count {
    let index = queue[head]; head += 1
    let x = index % size, y = index / size
    if x > 0 { enqueue(index - 1) }
    if x + 1 < size { enqueue(index + 1) }
    if y > 0 { enqueue(index - size) }
    if y + 1 < size { enqueue(index + size) }
}
var minX = size, minY = size, maxX = -1, maxY = -1
for index in 0..<(size * size) where !background[index] {
    let x = index % size, y = index / size
    minX = min(minX, x); maxX = max(maxX, x)
    minY = min(minY, y); maxY = max(maxY, y)
}
precondition(minX > 70 && minY > 70 && maxX < size - 70 && maxY < size - 70,
    "Artwork must retain the approved empty margins")

let context = bitmap(size, size)
let bounds = CGRect(x: 24, y: 28, width: 412, height: 412)
let backplate = CGPath(roundedRect: bounds, cornerWidth: 88, cornerHeight: 88, transform: nil)
// Keep the existing backplate, shadow footprint, and corner transparency.
context.saveGState()
context.setShadow(offset: CGSize(width: 0, height: -5), blur: 11,
    color: CGColor(gray: 0, alpha: 0.19))
context.addPath(backplate)
context.setFillColor(surfaceColor(24))
context.fillPath()
context.restoreGState()
context.saveGState()
context.addPath(backplate); context.clip()
let surface = CGGradient(colorsSpace: colorSpace,
    colors: [surfaceColor(24), surfaceColor(31)] as CFArray,
    locations: [0, 1])!
context.drawLinearGradient(surface, start: CGPoint(x: 230, y: 28),
    end: CGPoint(x: 230, y: 440), options: [])
context.restoreGState()
let edge = CGPath(roundedRect: bounds.insetBy(dx: 0.75, dy: 0.75),
    cornerWidth: 87.25, cornerHeight: 87.25, transform: nil)
context.addPath(edge)
context.setStrokeColor(CGColor(gray: 1, alpha: 0.08))
context.setLineWidth(1.5); context.strokePath()
context.saveGState()
context.clip(to: CGRect(x: 0, y: 230, width: 460, height: 230))
context.addPath(CGPath(roundedRect: bounds.insetBy(dx: 2, dy: 2),
    cornerWidth: 86, cornerHeight: 86, transform: nil))
context.setStrokeColor(CGColor(gray: 1, alpha: 0.04))
context.setLineWidth(2); context.strokePath()
context.restoreGState()

// Dematte only the outer four-pixel antialiasing fringe. Every interior artwork
// pixel remains identical; no resizing, repositioning, or replacement drawing.
var lamp = [UInt8](repeating: 0, count: size * size * 4)
var interiorPixels = 0, edgePixels = 0
for y in minY...maxY {
    for x in minX...maxX where !background[y * size + x] {
        let index = y * size + x, offset = index * 4
        var fringe = false
        for dy in -4...4 {
            for dx in -4...4 where abs(dx) + abs(dy) <= 4 {
                let nx = x + dx, ny = y + dy
                if nx >= 0 && ny >= 0 && nx < size && ny < size && background[ny * size + nx] { fringe = true }
            }
        }
        let minimum = min(originalPixels[offset], originalPixels[offset + 1], originalPixels[offset + 2])
        if fringe && minimum < 255 {
            let alpha = 255 - minimum
            lamp[offset] = originalPixels[offset] - minimum
            lamp[offset + 1] = originalPixels[offset + 1] - minimum
            lamp[offset + 2] = originalPixels[offset + 2] - minimum
            lamp[offset + 3] = alpha
            edgePixels += 1
        } else {
            for channel in 0..<4 { lamp[offset + channel] = originalPixels[offset + channel] }
            interiorPixels += 1
        }
    }
}
// Premultiplied source-over, with integer arithmetic for reproducible geometry.
let pixels = context.data!.assumingMemoryBound(to: UInt8.self)
for index in 0..<(size * size) {
    let offset = index * 4, alpha = Int(lamp[offset + 3])
    if alpha == 0 { continue }
    for channel in 0..<3 {
        pixels[offset + channel] = UInt8(min(255, Int(lamp[offset + channel])
            + (Int(pixels[offset + channel]) * (255 - alpha) + 127) / 255))
    }
    pixels[offset + 3] = 255
}
precondition(pixels[3] == 0, "Icon corners must remain transparent")
let image = context.makeImage()!
func writePNG(_ image: CGImage, _ url: URL) {
    let destination = CGImageDestinationCreateWithURL(url as CFURL, UTType.png.identifier as CFString, 1, nil)!
    CGImageDestinationAddImage(destination, image, nil)
    precondition(CGImageDestinationFinalize(destination), "PNG output failed")
}
writePNG(image, outputURL)
let encodedSource = CGImageSourceCreateWithURL(outputURL as CFURL, nil)!
let encodedImage = CGImageSourceCreateImageAtIndex(encodedSource, 0, nil)!
let decoded = bitmap(size, size)
decoded.draw(encodedImage, in: CGRect(x: 0, y: 0, width: size, height: size))
let decodedPixels = decoded.data!.assumingMemoryBound(to: UInt8.self)
var checkMinX = size, checkMinY = size, checkMaxX = -1, checkMaxY = -1, compared = 0
for index in 0..<(size * size) {
    let offset = index * 4
    if lamp[offset + 3] > 0 {
        let x = index % size, y = index / size
        checkMinX = min(checkMinX, x); checkMaxX = max(checkMaxX, x)
        checkMinY = min(checkMinY, y); checkMaxY = max(checkMaxY, y)
    }
    if !background[index] && lamp[offset + 3] == 255 {
        for channel in 0..<4 {
            precondition(decodedPixels[offset + channel] == originalPixels[offset + channel],
                "Encoded PNG changed an interior lamp pixel")
        }
        compared += 1
    }
}
precondition(checkMinX == minX && checkMinY == minY && checkMaxX == maxX && checkMaxY == maxY,
    "Lamp size or placement changed")
precondition(compared == interiorPixels && decodedPixels[3] == 0,
    "Interior preservation or corner transparency failed")
for (x, y) in [(230, 80), (230, 380), (60, 230)] {
    let offset = (y * size + x) * 4
    precondition((24...31).contains(Int(decodedPixels[offset]))
        && decodedPixels[offset] == decodedPixels[offset + 1]
        && decodedPixels[offset] == decodedPixels[offset + 2], "Backplate must use exact dark neutral RGB values")
}
print("Saved \(outputURL.path)")
print("Lamp bounds unchanged: x=\(minX)...\(maxX), y=\(minY)...\(maxY)")
print("\(interiorPixels) interior pixels identical; \(edgePixels) white-matted fringe pixels recomposited")
print("Output: \(size)×\(size) RGBA; #181818–#1F1F1F backplate; transparent corners")

if CommandLine.arguments.contains("--preview") {
    let previewURL = sourceDirectory.deletingLastPathComponent().appendingPathComponent("build/icon-preview-dark.png")
    try FileManager.default.createDirectory(at: previewURL.deletingLastPathComponent(), withIntermediateDirectories: true)
    let sheet = bitmap(600, 280)
    sheet.setFillColor(surfaceColor(18)); sheet.fill(CGRect(x: 0, y: 0, width: 600, height: 280))
    sheet.interpolationQuality = .high
    for (x, side) in [(24, 32), (88, 64), (184, 128), (336, 240)] {
        sheet.draw(image, in: CGRect(x: x, y: (280 - side) / 2, width: side, height: side))
    }
    writePNG(sheet.makeImage()!, previewURL)
    print("Preview: \(previewURL.path)")
}
