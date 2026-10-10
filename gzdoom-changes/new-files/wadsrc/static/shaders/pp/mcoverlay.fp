
// Minecraft passthrough: the hand, hotbar and screens over the finished scene (premultiplied alpha), and over one of
// Minecraft's screens the pointer, drawn here where the mouse is this frame (Minecraft's picture is a frame or two
// old: a pointer in it trails behind the hand).

layout(location=0) in vec2 TexCoord;
layout(location=0) out vec4 FragColor;

layout(binding=0) uniform sampler2D SceneTexture;
layout(binding=1) uniform sampler2D McOverlay;

void main()
{
	vec4 scene = texelFetch(SceneTexture, ivec2(gl_FragCoord.xy), 0);
	vec4 mc = texture(McOverlay, TexCoord);
	vec3 color = scene.rgb * (1.0 - mc.a) + mc.rgb;

	if (Cursor.z > 0.5)
	{
		// an arrow as Minecraft's screens are drawn: in "GUI pixels", as big as its interface scale makes them
		float unit = max(1.0, floor(min(View.x / 320.0, View.y / 240.0)));
		vec2 d = (TexCoord - Cursor.xy) * View.xy / unit;
		float row = floor(-d.y), column = floor(d.x);
		float inner = clamp(row, 0.0, 8.0);
		float width = inner < 6.0 ? inner + 1.0 : 9.0 - inner;
		if (row >= 0.0 && row < 9.0 && column >= 0.0 && column < width)
			color = vec3(1.0);
		else if (row >= -1.0 && row < 10.0 && column >= -1.0 && column < width + 1.0)
			color = vec3(0.0);
	}
	FragColor = vec4(color, scene.a);
}
