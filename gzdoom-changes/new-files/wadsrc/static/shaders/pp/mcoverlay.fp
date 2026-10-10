
// Minecraft passthrough: the hand, hotbar and screens over the finished scene (premultiplied alpha).

layout(location=0) in vec2 TexCoord;
layout(location=0) out vec4 FragColor;

layout(binding=0) uniform sampler2D SceneTexture;
layout(binding=1) uniform sampler2D McOverlay;

void main()
{
	vec4 scene = texelFetch(SceneTexture, ivec2(gl_FragCoord.xy), 0);
	vec4 mc = texture(McOverlay, TexCoord);
	FragColor = vec4(scene.rgb * (1.0 - mc.a) + mc.rgb, scene.a);
}
