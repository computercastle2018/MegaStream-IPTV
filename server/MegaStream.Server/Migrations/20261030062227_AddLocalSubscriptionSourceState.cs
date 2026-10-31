using Microsoft.EntityFrameworkCore.Migrations;

#nullable disable

namespace MegaStream.Server.Migrations
{
    /// <inheritdoc />
    public partial class AddLocalSubscriptionSourceState : Migration
    {
        /// <inheritdoc />
        protected override void Up(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.AddColumn<string>(
                name: "SourceContentHash",
                table: "RemoteProviderProfiles",
                type: "varchar(64)",
                maxLength: 64,
                nullable: true)
                .Annotation("MySql:CharSet", "utf8mb4");

            migrationBuilder.AddColumn<bool>(
                name: "SourceEnabled",
                table: "RemoteProviderProfiles",
                type: "tinyint(1)",
                nullable: true);

            migrationBuilder.AddColumn<string>(
                name: "SourceStatus",
                table: "RemoteProviderProfiles",
                type: "varchar(32)",
                maxLength: 32,
                nullable: true)
                .Annotation("MySql:CharSet", "utf8mb4");
        }

        /// <inheritdoc />
        protected override void Down(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.DropColumn(
                name: "SourceContentHash",
                table: "RemoteProviderProfiles");

            migrationBuilder.DropColumn(
                name: "SourceEnabled",
                table: "RemoteProviderProfiles");

            migrationBuilder.DropColumn(
                name: "SourceStatus",
                table: "RemoteProviderProfiles");
        }
    }
}
