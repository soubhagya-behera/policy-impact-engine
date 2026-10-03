# Design reference

Source: https://render.com/
Page: Render \| The cloud for builders

Measured from 338 rendered elements at 1536 x 826px. This is a sample of the current document and state, not the original design source.

## Color palette

Only observed CSS colors are listed. Usage labels describe where a color was found. Hex is an sRGB preview; retain the original CSS value for its color space and transparency.

Usage | Original CSS value | sRGB hex | Observed root properties | Occurrences
--- | --- | --- | --- | ---
Text | rgb(255, 255, 255) | #FFFFFF | --osano-toggle-on-thumb-color, --osano-button-reject-all-foreground-color, --osano-dialog-gpc-foreground-color, --osano-info-dialog-gpc-background-color, --color-white, --osano-button-accept-foreground-color, --osano-button-accept-border-color | 50
Text | rgb(107, 107, 107) | #6B6B6B | --color-gray-500 | 46
Text | rgb(209, 184, 255) | #D1B8FF | --color-purple-200 | 12
Text | rgb(13, 13, 13) | #0D0D0D |  | 5
Text | rgb(227, 227, 227) | #E3E3E3 | --color-text-secondary, --color-gray-100 | 3
Text | rgb(194, 158, 255) | #C29EFF | --color-purple-300 | 2
Text | rgb(92, 255, 184) | #5CFFB8 |  | 2
Text | rgb(234, 190, 149) | #EABE95 |  | 2
Text | rgb(246, 128, 255) | #F680FF |  | 1
Text | rgb(138, 214, 255) | #8AD6FF |  | 1
Text | rgb(250, 241, 163) | #FAF1A3 |  | 1
Text | rgb(240, 152, 158) | #F0989E |  | 1
Text | rgb(198, 251, 157) | #C6FB9D |  | 1
Text | rgb(143, 143, 143) | #8F8F8F | --color-gray-400 | 1
Surface | rgb(209, 184, 255) | #D1B8FF | --color-purple-200 | 6
Surface | rgb(13, 13, 13) | #0D0D0D |  | 5
Surface | rgb(255, 255, 255) | #FFFFFF | --osano-toggle-on-thumb-color, --osano-button-reject-all-foreground-color, --osano-dialog-gpc-foreground-color, --osano-info-dialog-gpc-background-color, --color-white, --osano-button-accept-foreground-color, --osano-button-accept-border-color | 5
Surface | rgb(0, 44, 111) | #002C6F | --color-blue-700 | 1
Border | rgb(39, 39, 39) | #272727 |  | 6
Border | rgb(255, 255, 255) | #FFFFFF | --osano-toggle-on-thumb-color, --osano-button-reject-all-foreground-color, --osano-dialog-gpc-foreground-color, --osano-info-dialog-gpc-background-color, --color-white, --osano-button-accept-foreground-color, --osano-button-accept-border-color | 3
Gradient | rgb(39, 39, 39) | #272727 |  | 3
Gradient | rgb(155, 82, 251) | #9B52FB |  | 2
Gradient | rgb(184, 255, 215) | #B8FFD7 |  | 2
Gradient | rgb(28, 0, 55) | #1C0037 |  | 1
Icon | rgb(0, 0, 0) | #000000 |  | 31
Icon | rgb(255, 255, 255) | #FFFFFF | --osano-toggle-on-thumb-color, --osano-button-reject-all-foreground-color, --osano-dialog-gpc-foreground-color, --osano-info-dialog-gpc-background-color, --color-white, --osano-button-accept-foreground-color, --osano-button-accept-border-color | 20
Icon | rgb(138, 5, 255) | #8A05FF |  | 15
Icon | rgb(39, 39, 39) | #272727 |  | 15
Icon | rgb(230, 218, 255) | #E6DAFF |  | 12
Icon | rgb(133, 133, 133) | #858585 |  | 12
Icon | rgb(13, 13, 13) | #0D0D0D |  | 6
Icon | rgb(231, 254, 212) | #E7FED4 |  | 6
Icon | rgb(242, 57, 255) | #F239FF |  | 2
Icon | rgb(195, 195, 195) | #C3C3C3 |  | 2
Icon | rgb(0, 200, 155) | #00C89B |  | 2
Icon | rgb(193, 193, 193) | #C1C1C1 |  | 1

## Typography

Role | Family | Size | Weight | Line height | Tracking | Text transform
--- | --- | --- | --- | --- | --- | ---
Heading | Roobert, "Roobert Fallback", sans-serif | 80px | 300 | 80px | -2.4px | none
Section heading | Roobert, "Roobert Fallback", sans-serif | 64px | 300 | 68px | -1.28px | none
Subheading | Roobert, "Roobert Fallback", sans-serif | 20px | 400 | 24px | -0.2px | none
Body | PPNeueMontreal, "PPNeueMontreal Fallback", sans-serif | 18px | 400 | 26px | 0.09px | none
Button | PPNeueMontreal, "PPNeueMontreal Fallback", sans-serif | 16px | 400 | 24px | normal | none

## Spacing

Value | Occurrences
--- | ---
2px | 20
12px | 2
16px | 12
20px | 13
23px | 12
24px | 16
30px | 12
40px | 2
60px | 5
95.0625px | 2
120px | 4
677.062px | 2

## Layout gaps

Value | Occurrences
--- | ---
6px | 6
10px | 12
16px | 2
20px | 2
40px | 3
60px | 2
190.125px | 2

## Corner radii

No values observed in this sample.

## Shadows

No values observed in this sample.

## Motion durations

Value | Occurrences
--- | ---
0.3s | 60
0.15s | 10
0.35s | 1

## Motion easing

Value | Occurrences
--- | ---
cubic-bezier(0.4, 0, 0.2, 1) | 70
cubic-bezier(0.8, 0.01, 0.11, 0.98) | 1

### Accessible keyframe names

- delay-overflow
- osano-load-scale
- fadeIn
- accordion-slide-down
- accordion-slide-up
- tooltip-slide-up-in
- tooltip-slide-up-out
- tooltip-slide-down-in
- tooltip-slide-down-out
- tooltip-slide-left-in
- tooltip-slide-left-out
- tooltip-slide-right-in

## Component recipes

Computed styles for the captured state. Selectors identify sampled elements; text and placeholders come from the page. Form values are excluded. These style specimens do not reconstruct child markup or uncaptured interaction states.

### Button 1

Observed text or placeholder: Start for free

```css
a.ease.transition-colors {
  align-items: center;
  backdrop-filter: none;
  background-color: rgb(255, 255, 255);
  background-image: none;
  border-bottom-color: rgb(39, 39, 39);
  border-bottom-style: solid;
  border-bottom-width: 0px;
  border-left-color: rgb(39, 39, 39);
  border-left-style: solid;
  border-left-width: 0px;
  border-radius: 0px;
  border-right-color: rgb(39, 39, 39);
  border-right-style: solid;
  border-right-width: 0px;
  border-top-color: rgb(39, 39, 39);
  border-top-style: solid;
  border-top-width: 0px;
  box-shadow: none;
  color: rgb(13, 13, 13);
  display: flex;
  font-family: PPNeueMontreal, "PPNeueMontreal Fallback", sans-serif;
  font-size: 16px;
  font-weight: 400;
  gap: 10px;
  justify-content: space-between;
  letter-spacing: 0.16px;
  line-height: 24px;
  opacity: 1;
  padding-bottom: 0px;
  padding-left: 24px;
  padding-right: 24px;
  padding-top: 0px;
  text-transform: none;
}
```

Ancestor backgrounds (outermost first):

Layer | Background color | Background image | Opacity
--- | --- | --- | ---
1 | rgb(13, 13, 13) | none | 1
2 | rgb(13, 13, 13) | none | 1
3 | rgb(13, 13, 13) | none | 1
4 | rgba(0, 0, 0, 0) | linear-gradient(to right bottom, rgb(28, 0, 55), rgba(0, 0, 0, 0) 20%) | 1

### Button 2

Observed text or placeholder: HIPAA on Render

```css
a.ease.transition-colors {
  align-items: center;
  backdrop-filter: none;
  background-color: rgba(0, 0, 0, 0);
  background-image: none;
  border-bottom-color: rgb(255, 255, 255);
  border-bottom-style: solid;
  border-bottom-width: 0.8px;
  border-left-color: rgb(255, 255, 255);
  border-left-style: solid;
  border-left-width: 0.8px;
  border-radius: 0px;
  border-right-color: rgb(255, 255, 255);
  border-right-style: solid;
  border-right-width: 0.8px;
  border-top-color: rgb(255, 255, 255);
  border-top-style: solid;
  border-top-width: 0.8px;
  box-shadow: none;
  color: rgb(255, 255, 255);
  display: flex;
  font-family: PPNeueMontreal, "PPNeueMontreal Fallback", sans-serif;
  font-size: 16px;
  font-weight: 400;
  gap: 10px;
  justify-content: space-between;
  letter-spacing: 0.16px;
  line-height: 24px;
  opacity: 1;
  padding-bottom: 0px;
  padding-left: 24px;
  padding-right: 24px;
  padding-top: 0px;
  text-transform: none;
}
```

Ancestor backgrounds (outermost first):

Layer | Background color | Background image | Opacity
--- | --- | --- | ---
1 | rgb(13, 13, 13) | none | 1
2 | rgb(13, 13, 13) | none | 1
3 | rgb(13, 13, 13) | none | 1

## Layout measurements

Element | Width | Display | Columns | Gap | Padding (T R B L)
--- | --- | --- | --- | --- | ---
main | 1521px | block | none | normal | 0px 0px 0px 0px
header | 1521px | block | none | normal | 0px 0px 0px 0px
section | 1521px | flex | none | normal | 0px 0px 0px 0px
section | 1521px | flex | none | normal | 0px 0px 0px 0px
footer | 1521px | block | none | normal | 0px 0px 0px 0px

## Responsive conditions

- `screen and (min-width: 768px)`
- `screen and (min-width: 376px)`
- `screen and (max-height: 800px) and (max-width: 1200px)`
- `(min-width: 420px)`
- `(min-width: 640px)`
- `(min-width: 768px)`
- `(min-width: 1024px)`
- `(min-width: 1180px)`
- `(min-width: 1440px)`
- `(min-width: 1600px)`
- `(min-width: 1760px)`
- `(max-width: 600px)`
- `(prefers-reduced-motion: reduce)`
- `(min-width: 1920px)`
- `(prefers-reduced-motion: no-preference)`
- `(max-width: 1522px)`
- `not all and (min-width: 1440px)`
- `not all and (min-width: 1180px)`
- `not all and (min-width: 1024px)`
- `not all and (min-width: 768px)`

## CSS custom properties

Names and values read from the root element; no new token names or ramps were generated.

Property | Value
--- | ---
--color-purple-200 | #d1b8ff
--osano-dialog-foreground-color-contrast | #ebebeb
--color-purple-300 | #c29eff
--osano-link-color-contrast | #23b97b
--osano-button-background-color-hover | #8e53ff
--osano-focus-outline-color | Highlight
--osano-info-dialog-toggle-off-track-color-hover | #4c4164
--color-green-25 | #f5fff9
--osano-button-deny-background-color-hover | #8e53ff
--osano-toggle-on-thumb-color | #FFFFFF
--osano-info-dialog-button-background-color-focus | #ebebeb
--color-pink-500 | #e615f2
--color-orange-600 | #6f4116
--osano-gpc-color | #37cd84
--osano-button-reject-all-foreground-color | #FFFFFF
--color-red-900 | #210305
--color-orange-800 | #1f1105
--color-blue-700 | #002c6f
--osano-dialog-type | bar
--osano-info-dialog-toggle-on-thumb-color-contrast | #ebebeb
--osano-toggle-on-track-color-disabled | #008d4f
--color-purple-800 | #2a0052
--color-orange-900 | #0d0702
--osano-info-dialog-toggle-off-thumb-color-focus | #4c4164
--osano-button-accept-background-color-contrast | #8e53ff
--osano-info-dialog-toggle-off-track-color | #382d50
--color-orange-50 | #faefe5
--color-blue-300 | #70c5ff
--color-text-secondary | #e3e3e3
--osano-button-background-color | #7A3FF1
--osano-button-manage-background-color-contrast | #8e53ff
--color-gray-300 | #b3b3b3
--osano-button-deny-background-color-contrast | #8e53ff
--color-yellow-400 | #c29800
--osano-info-dialog-button-background-color-hover | #8e53ff
--tw-ring-color | #3b82f680
--osano-info-dialog-button-background-color | #7A3FF1
--osano-dialog-gpc-foreground-color | #FFFFFF
--color-gray-500 | #6b6b6b
--osano-info-dialog-gpc-background-color | #ffffff
--font-neue-montreal-mono | "PPNeueMontrealMono", "PPNeueMontrealMono Fallback"
--osano-button-deny-foreground-color-contrast | #ebebeb
--color-green-500 | #009e7a
--osano-widget-color | #37cd8f
--color-white | #fff
--color-gray-400 | #8f8f8f
--color-blue-400 | #33acff
--color-pink-50 | #fce2fe
--osano-button-accept-foreground-color | #FFFFFF
--osano-toggle-on-track-color-hover | #23b97b
--osano-button-accept-background-color-focus | #ebebeb
--osano-button-accept-border-color | #fff
--color-yellow-300 | #e0bf00
--osano-widget-position | left
--osano-info-dialog-toggle-on-track-color-focus | #ebebeb
--osano-dialog-gpc-color | #37cd84
--osano-info-dialog-button-background-color-contrast | #8e53ff
--color-gray-100 | #e3e3e3
--osano-button-foreground-color-focus | #8e53ff
--color-orange-25 | #fdfaf6

## Capture coverage

- 0 stylesheet(s) could not be inspected. Computed styles are still measured.
- Sampling excludes hidden elements and Sitepeel tools. Offscreen rendered elements may be included. Counts refer to the sample, not the entire website.
- Colors are CSS values, not a screenshot pixel palette. Images, compositing and gradients can affect their visible appearance.
- Hover, focus, active states, other viewport sizes, iframe documents and shadow trees need separate captures.
- No inferred brand personality, invented colors, placeholder copy or unobserved code is presented as a page measurement.
